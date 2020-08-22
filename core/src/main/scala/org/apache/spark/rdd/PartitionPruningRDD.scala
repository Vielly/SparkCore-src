/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.rdd

import scala.reflect.ClassTag

import org.apache.spark.{NarrowDependency, Partition, TaskContext}
import org.apache.spark.annotation.DeveloperApi

private[spark] class PartitionPruningRDDPartition(idx: Int, val parentSplit: Partition)
  extends Partition {
  override val index = idx
}


/**
 * Represents a dependency between the PartitionPruningRDD and its parent. In this
 * case, the child RDD contains a subset of partitions of the parents'.
 */
/*
 *
 * @param rdd 父RDD的实例引用
 * @param partitionFilterFunc 是一个回调函数，作用是过滤出符合条件的父RDD的分区id集合
 * @tparam T
 */
private[spark] class PruneDependency[T](rdd: RDD[T], partitionFilterFunc: Int => Boolean)
  extends NarrowDependency[T](rdd) {

  /*
   * 现根据父RDD的所有partition集合，根据父RDD的partition的index使用过滤函数过滤出符合条件的（父RDD）partition，
   * 然后使用拉链操作将过滤出来的partition从 0 开始编号，最后使用父 partition （注意这里是Partition，不是分区id）和编号
   * 实例化新的 PartitionPruningRDDPartition 实例，并放入 partitions 集合中，这里就相当于对父 RDD 的 partition 进行
   * Filter剪枝操作。
   */
  @transient
  val partitions: Array[Partition] = rdd.partitions
    .filter(s => partitionFilterFunc(s.index)).zipWithIndex
    .map { case(split, idx) => new PartitionPruningRDDPartition(idx, split) : Partition }

  /*
   * 现根据子 partition 的 id 获取到父 RDD 对应的分区，然后获取父 RDD 的 partition 的 index ，该 index 是父 RDD 对应此
   * partition 的索引值，子RDD partition 和 父RDD partition的关系是 一对一的， 父RDD 和子RDD 的关系是 多对一，也可能是一对多，也可能是一对一。
   * 简言之，在窄依赖中，子RDD 的partition 和 父RDD 的 partition 的关系是 一对一的。
   */
  override def getParents(partitionId: Int): List[Int] = {
    List(partitions(partitionId).asInstanceOf[PartitionPruningRDDPartition].parentSplit.index)
  }
}


/**
 * :: DeveloperApi ::
 * An RDD used to prune RDD partitions/partitions so we can avoid launching tasks on
 * all partitions. An example use case: If we know the RDD is partitioned by range,
 * and the execution DAG has a filter on the key, we can avoid launching tasks
 * on partitions that don't have the range covering the key.
 */
@DeveloperApi
class PartitionPruningRDD[T: ClassTag](
    prev: RDD[T],
    partitionFilterFunc: Int => Boolean)
  extends RDD[T](prev.context, List(new PruneDependency(prev, partitionFilterFunc))) {

  override def compute(split: Partition, context: TaskContext): Iterator[T] = {
    firstParent[T].iterator(
      split.asInstanceOf[PartitionPruningRDDPartition].parentSplit, context)
  }

  override protected def getPartitions: Array[Partition] =
    dependencies.head.asInstanceOf[PruneDependency[T]].partitions
}


@DeveloperApi
object PartitionPruningRDD {

  /**
   * Create a PartitionPruningRDD. This function can be used to create the PartitionPruningRDD
   * when its type T is not known at compile time.
   */
  def create[T](rdd: RDD[T], partitionFilterFunc: Int => Boolean): PartitionPruningRDD[T] = {
    new PartitionPruningRDD[T](rdd, partitionFilterFunc)(rdd.elementClassTag)
  }
}
