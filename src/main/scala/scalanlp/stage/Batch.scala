/*
 Copyright 2009 David Hall, Daniel Ramage

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
*/
package scalanlp.stage;

import scala.reflect.Manifest;

import scalanlp.collection.immutable.HTMap;

/**
 * Represents a batch of elements that can be treated as just
 * an iterable of V.  Use the static constructors in Batch
 * to create instances.
 * 
 * @author dramage
 */
trait Batch[+V] {
  /** An iterable of items ordered by their number. */
  def items : Iterable[Item[V]];
  
  /** An iterable of the values in this map (unboxes items). */
  def values : Iterable[V] =
    items.projection.map(_.value);
  
  /** Number of items in this batch. */
  // TODO: in scala 2.8.0 this can just delegate to items.size
  lazy val size = {
    var s = 0;
    for (item <- items.elements) {
      s += 1;
    }
    s;
  }
  
  /** Transforms the items of the batch according to the given function. */
  def map[O](f : V => O) : Batch[O] = {
    def mapper(item : Item[V]) = {
      try {
        item.map(f)
      } catch {
        case ex : Throwable =>
          throw new BatchException(item, ex);
      }
    }
    Batch.fromItems[O](items.projection.map(mapper));
  }
  
  /** Filters out items from the batch according to the given funciton. */
  def filter(f : V => Boolean) : Batch[V] = {
    def filterer(item : Item[V]) = {
      try {
        f(item.value);
      } catch {
        case ex : Throwable =>
          throw new BatchException(item, ex);
      }
    }
    Batch.fromItems(items.projection.filter(filterer));
  }
  
  /** Takes the first n elements. */
  def take(n : Int) : Batch[V] =
    Batch.fromItems(items.projection.take(n));

  /** Drops the first n elements. */
  def drop(n : Int) : Batch[V] =
    Batch.fromItems(items.projection.drop(n));
  
  /** Zips together two batches. */
  def zip[O](that : Batch[O]) = {
    Batch.Zip(this,that).map(
      seq => (seq(0).asInstanceOf[Option[V]],
              seq(1).asInstanceOf[Option[O]]
    ));
  }
}

/**
 * An exception thrown by Batch when a map or filter filter fails on
 * a given row.
 * 
 * @author dramage
 */
class BatchException(item : Item[_], cause : Throwable)
extends RuntimeException("Unable to process " + item +
                           " item " + item.number, cause);

/**
 * Static constructors for creating batches.
 */
object Batch {
  def fromItems[V](inItems : Iterable[Item[V]]) = new Batch[V] {
    override def items = inItems;
  }
  
  def fromIterable[V](inItems : Iterable[V]) = new Batch[V] {
    override def items = new Iterable[Item[V]] {
      override def elements = {
        for ((v,i) <- inItems.elements.zipWithIndex) yield
          Item(i, v);
      }
    }
  }
  
  /** Zips together two batches. */
  def zip[A,B](batchA : Batch[A], batchB : Batch[B]) =
    (batchA zip batchB);
  
  /** Zips together three batches. */
  def zip[A,B,C](batchA : Batch[A], batchB : Batch[B], batchC : Batch[C])
  : Batch[(Option[A],Option[B],Option[C])] = {
    Batch.Zip(batchA,batchB,batchC).map(
      seq => (seq(0).asInstanceOf[Option[A]],
              seq(1).asInstanceOf[Option[B]],
              seq(2).asInstanceOf[Option[C]]
    ));
  }
  
  /**
   * A single batch consisting of items selected from the incoming list of batches
   * by matching on item number.  This batch iterates over Array[Option[V]] where
   * the sequence order matches the order of the batches passed as input, and
   * the next value from each batch is placed into the corresponding array cell.  If
   * a batch is missing an element, None is included in the array at that position.
   * 
   * @author dramage
   */
  class Zip[V](batches : Batch[V] *) extends Batch[Seq[Option[V]]] {
    override def items = new Iterable[Item[Seq[Option[V]]]] {
      override def elements = new Iterator[Item[Seq[Option[V]]]] {
        val iterators = batches.map(_.items.elements.buffered.advanced);
      
        override def hasNext =
          iterators.exists(_.hasNext);
      
        override def next = {
          val peeks = for (iter <- iterators) yield { if (iter.hasNext) Some(iter.peek(0)) else None };
          val nextNum = peeks.filter(_.isDefined).map(_.get.number).foldLeft(Int.MaxValue)(_ min _);

          val values = (
            for ((peek, iterator) <- peeks.elements zip iterators.elements) yield
              if (peek.isDefined && peek.get.number == nextNum) Some(iterator.next.value) else None
          ).toList;
        
          Item(nextNum, values);
        }
      }
    }
  }

  object Zip {
    def apply[V](batches : Batch[V] *) =
      new Batch.Zip[V](batches :_*);
  }
}

/** 
 * An item represents a single item corresponding to the
 * given numbered item from the origin.
 * 
 * @param origin The (original) source of this data item.
 * @param number The number of this item in the origin.
 * @param value The value of this item.
 */
case class Item[+V](number : Int, value : V) {
  def map[O](f : V => O) =
    Item[O](number, f(value));
}


/**
 * A mapper is a stage that transforms the data from an Iterable[Option[I]] to an
 * Iterable[Option[O]] but adds no metadata.  See the MapperN variants for mappers
 * that can read metadata during the mapping process.
 * 
 * @author dramage
 */
abstract class Mapper[I,O](implicit mI : Manifest[I], mO : Manifest[O])
extends Stage[Any,Any,Batch[I],Batch[O]] {
  /** Transforms the input data without using metadata. */
  def map(row : I) : O;

  /** Calls map. */
  override def process[M](parcel : Parcel[M,Batch[I]]) : Parcel[M, Batch[O]] =
    parcel.withHistory(this).map(_.map(map));
}

/**
 * Companion object to Mapper with a static constructor for simple function mappers.
 * 
 * @author dramage
 */
object Mapper {
  def apply[I,O](f : I => O)(implicit mI : Manifest[I], mO : Manifest[O]) = new Mapper[I,O] {
    override def map(row : I) : O = f(row);
    override def toString = "Mapper("+f.toString+")";
  }
}

/**
 * Mapper1[I,O,M1] acts like a Mapper[I,O] that depends on a single piece
 * of metadata of type M1 that was provided by a previous stage.
 * 
 * @author dramage
 */
abstract class Mapper1[I,O,M1](implicit m1 : Manifest[M1], mI : Manifest[I], mO : Manifest[O])
extends Stage[M1,M1,Batch[I],Batch[O]] {
  /** Transforms the input data using metadata of type M1. */
  def map(meta : M1)(data : I) : O;

  /** Calls map. */
  override def process[M<:M1](parcel : Parcel[M,Batch[I]]) : Parcel[M, Batch[O]] = {
    val mapper = map(parcel.meta.get[M1]) _;
    parcel.withHistory(this).map(_.map(mapper));
  }
}

/**
 * A filter is a stage that removes elements from the input Iterable.
 * If the input option is None, the output is None.  If the input is Some(x)
 * and filter(x) is false, replaces that element with None.  Otherwise it
 * reutrns Some(x) unchanged.
 */
abstract class Filter[I](implicit mI : Manifest[I])
extends Stage[Any,Any,Batch[I],Batch[I]] {
  /**
   * Filters the input data without using metadata.  If the return value is
   * true, keeps the record.  If false, filters it.
   */
  def filter(row : I) : Boolean;
    
  /** Calls filter. */
  override def process[M](parcel : Parcel[M,Batch[I]])
  : Parcel[M, Batch[I]] =
    parcel.withHistory(this).map(_.filter(filter));
}

/**
 * Companion object to Filter with a static constructor for simple function filters.
 * 
 * @author dramage
 */
object Filter {
  def apply[I](f : I => Boolean)(implicit mI : Manifest[I]) = new Filter[I] {
    override def filter(row : I) = f(row);
    override def toString = "Filter("+f.toString+")";
  }
}

/**
 * Takes the first n elements from the batch.
 * 
 * @author dramage
 */
case class Take[I](n : Int)(implicit mI : Manifest[I])
extends Stage[Any,Any,Batch[I],Batch[I]] {
  override def process[M](parcel : Parcel[M,Batch[I]]) : Parcel[M, Batch[I]] =
    parcel.withHistory(this).map(_.take(n));
}

/**
 * Drops the first n elements from the batch.
 * 
 * @author dramage
 */
case class Drop[I](n : Int)(implicit mI : Manifest[I])
extends Stage[Any,Any,Batch[I],Batch[I]] {
  override def process[M](parcel : Parcel[M,Batch[I]]) : Parcel[M, Batch[I]] =
    parcel.withHistory(this).map(_.drop(n));
}
