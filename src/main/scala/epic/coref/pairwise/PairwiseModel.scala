package epic.coref
package pairwise

import epic.framework._
import breeze.linalg._
import breeze.linalg.DenseVector
import breeze.util.Index
import java.util
import collection.{immutable, mutable}
import collection.mutable.ArrayBuffer
import collection.immutable.IndexedSeq

/**
 *
 * @author dlwh
 */

class PairwiseModel(feat: PairwiseFeaturizer, val featureIndex: Index[Feature]) extends Model[IndexedCorefInstance] with StandardExpectedCounts.Model {
  type Inference = PairwiseInference

  def initialValueForFeature(f: Feature): Double = 0.0

  def inferenceFromWeights(weights: DenseVector[Double]) = {
    new PairwiseInference(feat, featureIndex, weights)
  }


}




class PairwiseInference(feat: PairwiseFeaturizer, val featureIndex: Index[Feature],
                        weights: DenseVector[Double]) extends GoldGuessInference[IndexedCorefInstance] {
  type ExpectedCounts = StandardExpectedCounts[Feature]

  def emptyCounts: ExpectedCounts = StandardExpectedCounts.zero(featureIndex)

  def goldCounts(inst: IndexedCorefInstance) = {
    val ec = emptyCounts
    var ll = ec.loss
    val ecounts = ec.counts
    for (cluster <- inst.goldClusters) {
      var isFirst = true
      for (i <- cluster) {
        if (isFirst) {
          ll += weights dot inst.featuresFor(0, i)
          ecounts += inst.featuresFor(0, i)
          isFirst = false
        } else {
          val scores = DenseVector.zeros[Double](i)
          util.Arrays.fill(scores.data, Double.NegativeInfinity)
          for (j <- cluster if j < i) {
            scores(j) = weights dot inst.featuresFor(j, i)
          }
          val sm = softmax(scores)
          ll += sm

          for (j <- cluster if j < i) {
            addIntoScale(ecounts, inst.featuresFor(j, i), math.exp(scores(j) - sm))
          }
        }
      }


    }
    ec
  }

  def guessCounts(inst: IndexedCorefInstance) = {
    val ec = emptyCounts
    var ll = ec.loss
    val ecounts = ec.counts
    for (i <- 1 to inst.numMentions) {
      val scores = DenseVector.zeros[Double](i)
      for (j <- 0 until i) {
        scores(j) = weights dot inst.featuresFor(j, i)
      }
      val sm = softmax(scores)
      ll += sm

      for (j <- 0 until i) {
        addIntoScale(ecounts, inst.featuresFor(j, i), math.exp(scores(j) - sm))
      }
    }
    ec
  }

  private def addIntoScale(v: DenseVector[Double], sv: SparseVector[Double], scale: Double) {
    if (scale != 0) {
      var i = 0
      val bi = sv.index
      val bd = sv.data
      val ad = v.data
      val aoff = v.offset
      val astride = v.stride
      while (i < sv.used) {
        ad(aoff + astride * bi(i)) += bd(i) * scale
        i += 1
      }
    }
  }

  def decode(inst: IndexedCorefInstance, forceMergeSingletons: Boolean): IndexedSeq[Set[MentionCandidate]] = {
    val links = Array.fill(inst.numMentions + 1)(null: mutable.BitSet)
    val nonRoot = new Array[Int](inst.numMentions + 1)
    val trueClusters = ArrayBuffer[mutable.BitSet]()
    for (i <- 1 to inst.numMentions) {
      var maxJ = -1
      var maxScore = Double.NegativeInfinity
      var maxNotRoot = -1
      var maxNotRootScore = Double.NegativeInfinity
      for (j <- 0 until i) {
        val score = weights dot inst.featuresFor(j, i)
        if (score > maxScore) {
          maxJ = j
          maxScore = score
        }
        if (j != 0 && score > maxNotRootScore) {
          maxNotRoot = j
          maxNotRootScore = score
        }

      }
      if (maxJ == 0) {
        links(i) = mutable.BitSet(i)
        trueClusters += links(i)
        nonRoot(i) = maxNotRoot
      } else {
        links(i) = links(maxJ)
        links(i) += i
      }
    }

    if (forceMergeSingletons) {
      // go in reverse and merge things as necessary
      for (i <- inst.numMentions to 1 by -1) {
        assert(links(i) != null, i)
        if (links(i).size == 1 && nonRoot(i) != -1) {
          links(nonRoot(i)) ++= links(i)
          trueClusters -= links(i)
        }
      }
    }

    {
      for (cluster <- trueClusters) yield {
        for (i <- cluster) yield inst.unindexed.mentions(i - 1)
      }.toSet
    }.toIndexedSeq
  }
}