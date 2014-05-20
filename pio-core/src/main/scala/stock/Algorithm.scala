package io.prediction.stock

import io.prediction.{ Algorithm, BaseAlgoParams }
import scala.math
import org.saddle._
import org.saddle.index.IndexTime

import scala.collection.mutable.{ Map => MMap }
import com.github.nscala_time.time.Imports._

import breeze.linalg.{DenseMatrix, DenseVector}
import breeze.stats.{ mean, meanAndVariance }
import nak.regress.LinearRegression
import scala.collection.mutable.ArrayBuffer

class RegressionAlgorithm 
extends Algorithm[TrainingData, Feature, Target, Model, BaseAlgoParams] {
  private def getRet(logPrice: Frame[DateTime, String, Double], d: Int) = 
    (logPrice - logPrice.shift(d)).mapVec[Double](_.fillNA(_ => 0.0))

  private def regress(
    ret1d: Series[DateTime, Double],
    ret1w: Series[DateTime, Double],
    ret1m: Series[DateTime, Double],
    retF1d: Series[DateTime, Double]) = {
    val array = (
      Seq(ret1d, ret1w, ret1m).map(_.toVec.contents).reduce(_ ++ _) ++
      Array.fill(ret1d.length)(1.0))
    val target = DenseVector[Double](retF1d.toVec.contents)
    val m = DenseMatrix.create[Double](ret1d.length, 4, array)
    val result = LinearRegression.regress(m, target)
    result
  }

  // Build a linear model
  // ret(-1) = a * ret_1d + b * ret_1w + c * ret_1m + d
  def train(trainingData: TrainingData): Model = {
    val price = trainingData.price
    val logPrice = price.mapValues(math.log)

    val ret1d = getRet(logPrice, 1)
    val ret1w = getRet(logPrice, 5)
    val ret1m = getRet(logPrice, 22)
    val retF1d = getRet(logPrice, -1)

    val timeIndex = price.rowIx
    val firstIdx = 25
    val lastIdx = timeIndex.length

    val tickers = price.colIx.toVec.contents

    val tickerModelMap = tickers.map(ticker => {
      val model = regress(
        ret1d.firstCol(ticker).slice(firstIdx, lastIdx),
        ret1w.firstCol(ticker).slice(firstIdx, lastIdx),
        ret1m.firstCol(ticker).slice(firstIdx, lastIdx),
        retF1d.firstCol(ticker).slice(firstIdx, lastIdx))
      (ticker, model)
    }).toMap

    new Model(data = tickerModelMap)
  }

  private def predictOne(
    coef: DenseVector[Double],
    price: Series[DateTime, Double]): Double = {
    val shifts = Seq(0, 1, 5, 22)
    val sp = shifts
      .map(s => (s, math.log(price.raw(price.length - s - 1))))
      .toMap

    val vec = DenseVector[Double](
      sp(0) - sp(1),
      sp(0) - sp(5),
      sp(0) - sp(22),
      1)
   
    val p = coef.dot(vec)
    return p 
  }

  def predict(model: Model, feature: Feature): Target = {
    val price: Frame[DateTime, String, Double] = feature.data
    val modelData = model.data
    val prediction = price.colIx.toVec.contents
    // If model doesn't have the data, skip
    .filter{ ticker => model.data.contains(ticker) }  
    .map{ ticker => {
      val p = predictOne(
        modelData(ticker), 
        price.firstCol(ticker))
      (ticker, p)
    }}.toMap
    new Target(data = prediction)
  }

}
