package com.blinkbox.books.marvin.magrathea.message

import com.blinkbox.books.marvin.magrathea.Json4sExtensions._
import org.json4s.JsonAST.JObject
import org.json4s.JsonDSL._
import org.json4s._
import org.json4s.jackson.JsonMethods._

import scala.collection.mutable
import scala.language.implicitConversions

/**
 * Annotating the document means that every non-object and non-array field has a value and a source.
 * For objects and classified-arrays, each child is converted to a field with a value and a source.
 * Non-classified arrays are converted to fields with a value and a source.
 */
object DocumentAnnotator {
  case class MissingSourceException(d: JValue) extends RuntimeException(
    s"Cannot merge document without 'source' field: ${compact(d)}")

  /** Change the document so that every field has a reference to its source. */
  def annotate(doc: JValue): JValue = {
    val oldSrc = (doc \ "source").toOption.getOrElse(throw MissingSourceException(doc))
    val result = doAnnotate(doc.removeDirectField("source"), oldSrc.sha1)
    val annotated = result \\ "source" match {
      case JObject(sources) => sources.exists(_._2 == JString(oldSrc.sha1))
      case x => x == JString(oldSrc.sha1)
    }
    if (doc.children.size == 1 || annotated) result.replaceDirectField("source", oldSrc.sha1 -> oldSrc)
    else result.replaceDirectField("source", oldSrc)
  }

  /** For a value to be annotated, it has to have only two children: value and source. */
  def isAnnotated(v: JValue): Boolean =
    (v.children.size == 2) && (v \ "value" != JNothing) && (v \ "source" != JNothing)

  /** For an array to be classified, all of its items must have a classification field. */
  private def isClassified(arr: List[JValue]): Boolean = (arr.size > 0) &&
    arr.forall(v => (v \ "classification" != JNothing) || (v \ "value" \ "classification" != JNothing))

  private def doAnnotate(doc: JValue, srcHash: String): JValue = doc match {
    case JObject(xs) => JObject(annotateFields(xs, srcHash))
    case JArray(xs) if isClassified(xs) => JArray(uniquelyClassify(annotateArrays(xs, srcHash)))
    case JArray(xs) => annotateValue(xs, srcHash)
    case x => if (isAnnotated(x)) x else annotateValue(x, srcHash)
  }

  private def annotateFields(vs: List[JField], srcHash: String): List[JField] = vs match {
    case Nil => Nil
    case (xn, xv) :: xs if isAnnotated(xv) => JField(xn, xv) :: annotateFields(xs, srcHash)
    case (xn, xv) :: xs => JField(xn, doAnnotate(xv, srcHash)) :: annotateFields(xs, srcHash)
  }

  private def annotateArrays(vs: List[JValue], srcHash: String): List[JValue] = vs match {
    case Nil => Nil
    case x :: xs if isAnnotated(x) => x :: annotateArrays(xs, srcHash)
    case x :: xs => annotateValue(x, srcHash) :: annotateArrays(xs, srcHash)
  }

  private def annotateValue(v: JValue, srcHash: String): JValue = ("value" -> v) ~ ("source" -> srcHash)

  /** creating a unique classified array by merging any duplicates. */
  private def uniquelyClassify(arr: List[JValue]): List[JValue] = {
    val seen = mutable.Map.empty[JValue, JValue]
    for (x <- arr) {
      val key = x \ "value" \ "classification"
      seen += key -> seen.get(key).map(_ merge x).getOrElse(x)
    }
    seen.values.toList
  }
}
