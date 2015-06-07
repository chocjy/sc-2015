package org.apache.spark.mllib.linalg.distributed
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.SparkConf
import org.apache.spark.mllib.linalg.{Matrices, DenseMatrix, Matrix, DenseVector, Vector, SparseVector}
import org.apache.spark.mllib.linalg.distributed._
import breeze.linalg.{DenseMatrix => BDM, DenseVector => BDV, Axis, qr, svd, sum, SparseVector => BSV}
import math.{ceil, log}

import spray.json._
import DefaultJsonProtocol._
import java.io.{File, PrintWriter}

object CX {
  def fromBreeze(mat: BDM[Double]): DenseMatrix = {
    // FIXME: does not support strided matrices (e.g. views)
    new DenseMatrix(mat.rows, mat.cols, mat.data, mat.isTranspose)
  }

  // Returns `mat.transpose * mat * rhs`
  def multiplyGramianBy(mat: IndexedRowMatrix, rhs: DenseMatrix): DenseMatrix = {
    val rhsBrz = rhs.toBreeze.asInstanceOf[BDM[Double]]
    val result =
      mat.rows.treeAggregate(BDM.zeros[Double](mat.numCols.toInt, rhs.numCols))(
        seqOp = (U: BDM[Double], row: IndexedRow) => {
          val rowBrz = row.vector.toBreeze.asInstanceOf[BSV[Double]]
          val tmp: BDV[Double] = rhsBrz.t * rowBrz
          // performs a rank-1 update:
          //   U += outer(row.vector, tmp)
          for(ipos <- 0 until rowBrz.index.length) {
            val i = rowBrz.index(ipos)
            val ival = rowBrz.data(ipos)
            for(j <- 0 until tmp.length) {
              U(i, j) += ival * tmp(j)
            }
          }
          U
        },
        combOp = (U1, U2) => U1 += U2
      )
    fromBreeze(result)
  }

  def transposeMultiply(mat: IndexedRowMatrix, rhs: DenseMatrix): DenseMatrix = {
    require(mat.numRows == rhs.numRows)
    val rhsBrz = rhs.toBreeze.asInstanceOf[BDM[Double]]
    val result =
      mat.rows.treeAggregate(BDM.zeros[Double](mat.numCols.toInt, rhs.numCols))(
        seqOp = (U: BDM[Double], row: IndexedRow) => {
          val rowIdx = row.index.toInt
          val rowBrz = row.vector.toBreeze.asInstanceOf[BSV[Double]]
          // performs a rank-1 update:
          //   U += outer(row.vector, rhs(row.index, ::))
          for(ipos <- 0 until rowBrz.index.length) {
            val i = rowBrz.index(ipos)
            val ival = rowBrz.data(ipos)
            for(j <- 0 until rhs.numCols) {
              U(i, j) += ival * rhsBrz(rowIdx, j)
            }
          }
          U
        },
        combOp = (U1, U2) => U1 += U2
      )
    fromBreeze(result)
  }

  // returns `mat.transpose * randn(m, rank)`
  def gaussianProjection(mat: IndexedRowMatrix, rank: Int): DenseMatrix = {
    val rng = new java.util.Random
    transposeMultiply(mat, DenseMatrix.randn(mat.numRows.toInt, rank, rng))
  }

  def main(args: Array[String]) = {
    val conf = new SparkConf().setAppName("CX")
    conf.set("spark.task.maxFailures", "1")
    val sc = new SparkContext(conf)

    if(args(0) == "test") {
      testMain(sc, args.tail)
    } else {
      appMain(sc, args)
    }
  }

  def appMain(sc: SparkContext, args: Array[String]) = {
    if(args.length != 8) {
      Console.err.println("Expected args: [csv|idxrow] inpath nrows ncols outpath rank slack niters")
      System.exit(1)
    }

    val matkind = args(0)
    val inpath = args(1)
    val shape = (args(2).toInt, args(3).toInt)
    val outpath = args(4)

    // rank of approximation
    val rank = args(5).toInt

    // extra slack to improve the approximation
    val slack = args(6).toInt

    // number of power iterations to perform
    val numIters = args(7).toInt

    val k = rank + slack
    val mat =
      if(matkind == "csv") {
        val nonzeros = sc.textFile(inpath).map(_.split(",")).
        map(x => new MatrixEntry(x(1).toLong, x(0).toLong, x(2).toDouble))
        val coomat = new CoordinateMatrix(nonzeros, shape._1, shape._2)
        val mat = coomat.toIndexedRowMatrix()
        //mat.rows.saveAsObjectFile(s"hdfs:///$name.rowmat")
        mat
      } else if(matkind == "idxrow") {
        val rows = sc.objectFile[IndexedRow](inpath)
        new IndexedRowMatrix(rows, shape._1, shape._2)
      } else {
        throw new RuntimeException(s"unrecognized matkind: $matkind")
      }
    mat.rows.cache()

    /* perform randomized SVD of A' */
    var Y = gaussianProjection(mat, k).toBreeze.asInstanceOf[BDM[Double]]
    for(i <- 0 until numIters) {
      Y = multiplyGramianBy(mat, fromBreeze(Y)).toBreeze.asInstanceOf[BDM[Double]]
    }
    val Q = qr.reduced.justQ(Y)
    assert(Q.cols == k)
    val B = mat.multiply(fromBreeze(Q)).toBreeze.asInstanceOf[BDM[Double]].t
    val Bsvd = svd.reduced(B)
    // Since we computed the randomized SVD of A', unswap U and V here
    // to get back to svd(A) = U S V'
    val V = (Q * Bsvd.U).apply(::, 0 until rank)
    val S = Bsvd.S(0 until rank)
    val U = Bsvd.Vt(0 until rank, ::).t

    /* compute leverage scores */
    val rowlev = sum(U :^ 2.0, Axis._1)
    val rowp = rowlev / rank.toDouble
    val collev = sum(V :^ 2.0, Axis._1)
    val colp = collev / rank.toDouble
    assert(rowp.length == mat.numRows)
    assert(colp.length == mat.numCols)

    /* write output */
    val json = Map(
      "singvals" -> S.toArray.toSeq,
      "rowp" -> rowp.toArray.toSeq,
      "colp" -> colp.toArray.toSeq
    ).toJson
    val outw = new PrintWriter(new File(outpath))
    outw.println(json.compactPrint)
    outw.close()
  }

  def loadMatrixA(sc: SparkContext, fn: String) = {
    val input = scala.io.Source.fromFile(fn).getLines()
    require(input.next() == "%%MatrixMarket matrix coordinate real general")
    val dims = input.next().split(' ').map(_.toInt)
    val seen = BDM.zeros[Int](dims(0), dims(1))
    val entries = input.map(line => {
      val toks = line.split(" ")
      val i = toks(0).toInt - 1
      val j = toks(1).toInt - 1
      val v = toks(2).toDouble
      require(toks.length == 3)
      new MatrixEntry(i, j, v)
    }).toSeq
    require(entries.length == dims(2))
    new CoordinateMatrix(sc.parallelize(entries, 1), dims(0), dims(1)).toIndexedRowMatrix
  }

  def loadMatrixB(fn: String) = {
    val input = scala.io.Source.fromFile(fn).getLines()
    require(input.next() == "%%MatrixMarket matrix coordinate real general")
    val dims = input.next().split(' ').map(_.toInt)
    val seen = BDM.zeros[Int](dims(0), dims(1))
    val result = BDM.zeros[Double](dims(0), dims(1))
    var count = 0
    input.foreach(line => {
      val toks = line.split(" ")
      require(toks.length == 3)
      val i = toks(0).toInt - 1
      val j = toks(1).toInt - 1
      val v = toks(2).toDouble
      require(i >= 0 && i < dims(0))
      require(j >= 0 && j < dims(1))
      require(seen(i, j) == 0)
      seen(i, j) = 1
      result(i, j) = v
      if(v != 0) count += 1
    })
    //assert(count == dims(2))
    fromBreeze(result)
  }

  def writeMatrix(mat: DenseMatrix, fn: String) = {
    val writer = new java.io.FileWriter(new java.io.File(fn))
    writer.write("%%MatrixMarket matrix coordinate real general\n")
    writer.write(s"${mat.numRows} ${mat.numCols} ${mat.numRows*mat.numCols}\n")
    for(i <- 0 until mat.numRows) {
      for(j <- 0 until mat.numCols) {
        writer.write(f"${i+1} ${j+1} ${mat(i, j)}%f\n")
      }
    }
    writer.close
  }

  def testMain(sc: SparkContext, args: Array[String]) = {
    val A = loadMatrixA(sc, args(0))
    val B = fromBreeze(BDM.ones[Double](1024, 32))
    val X = multiplyGramianBy(A, B)
    writeMatrix(X, "result.mtx")
  }
}
