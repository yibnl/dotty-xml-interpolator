package dotty.xml.interpolator
package internal

import scala.language.implicitConversions
import scala.quoted.*
import dotty.xml.interpolator.internal.Tree.*

import scala.annotation.tailrec
import scala.xml.Node.unapplySeq

// TODO: are placeholders always in a preorder? then we can use Seq instead of Map, with special handling for attributes
object FillPlaceholders {
  def apply(nodes: Seq[Node], refNodes: Seq[scala.xml.Node]): Option[Map[Int, Any]] = {
    // remove comments
    val nodes1 = nodes.filter {
      case _: Comment => false
      case _ => true
    }
    val refNodes1 = refNodes.filter {
      case _: scala.xml.Comment => false
      case _ => true
    }

    if (nodes1.size == 1) findInNode(nodes1.head, refNodes1.head)
    else findInNodes(nodes1, refNodes1)
  }

  // string equality where (null == "")
  def strEq(s1: String, s2: String): Boolean =
    (if s1 == null then "" else s1) == (if s2 == null then "" else s2)

  // expects comments to be removed
  private def findInNode(node: Node, refNode: scala.xml.Node): Option[Map[Int, Any]] = {
    (node, refNode) match {
      case (group: Group, refGroup: scala.xml.Group)                 => findInGroup(group, refGroup)
      case (elem: Elem, refElem: scala.xml.Elem)                     => findInElem(elem, refElem)
      case (text: Text, refText: scala.xml.Text)                     => findInText(text, refText)
      case (placeholder: Placeholder, data)                          => fillPlaceholder(placeholder, data)
      case (pcData: PCData, refPCData: scala.xml.PCData)             => findInPCData(pcData, refPCData)
      case (procInstr: ProcInstr, refProcInstr: scala.xml.ProcInstr) => findInProcInstr(procInstr, refProcInstr)
      case (entityRef: EntityRef, refEntityRef: scala.xml.EntityRef) => findInEntityRef(entityRef, refEntityRef)
      case (unparsed: Unparsed, refUnparsed: scala.xml.Unparsed)     => findInUnparsed(unparsed, refUnparsed)

      // assertions
      case (_: Comment, _)           => throw IllegalStateException("comment passed as first argument to findInNode")
      case (_, _: scala.xml.Comment) => throw IllegalStateException("comment passed as second argument to findInNode")

      // non-matching nodes
      case _ => None
    }
  }

  private def findInNodes(nodes: Seq[Node], refNodes: Seq[scala.xml.Node]): Option[Map[Int, Any]] = {
    if nodes.length != refNodes.length then
      return None

    nodes
      .filter {
        case _: Comment => false
        case _ => true
      }
      .zip(refNodes.filter {
        case _: scala.xml.Comment => false
        case _ => true
      })
      .foldLeft(Some(Map()): Option[Map[Int, Any]]) {
        case (Some(map), (node, ref)) =>
          for fromNode <- findInNode(node, ref)
          yield map ++ fromNode
        case (None, _) =>
          return None
      }
  }

  private def findInGroup(group: Group, refGroup: scala.xml.Group): Option[Map[Int, Any]] =
    findInNodes(group.nodes, refGroup.nodes)

  private def findInElem(elem: Elem, refElem: scala.xml.Elem): Option[Map[Int, Any]] = {
    // convert from linked list representation to a Seq of scala.xml.MetaData where each element's next is Null
    @tailrec
    def extractAttributes(metadata: scala.xml.MetaData, attrs: Seq[scala.xml.Attribute] = Seq()): Seq[scala.xml.Attribute] =
      metadata match
        case scala.xml.Null => attrs
        case attr: scala.xml.Attribute => extractAttributes(attr.next, attr.copy(scala.xml.Null) +: attrs)

    val refAttributes = extractAttributes(refElem.attributes)
    if !strEq(elem.prefix, refElem.prefix) then
      return None

    if !strEq(elem.label, refElem.label) then
      return None

    for
      fromAttributes <- findInAttributes(elem.attributes, refAttributes)
      fromChildren <- findInNodes(elem.children, refElem.child)
    yield
      fromAttributes ++ fromChildren
  }

  private def findInAttributes(attributes: Seq[Attribute], refAttributes: Seq[scala.xml.Attribute]): Option[Map[Int, Any]] = {
    if attributes.length != refAttributes.length then
      return None

    val attributes1 = attributes.sortBy(_.name)
    val refAttributes1 = refAttributes.sortBy { a =>
      if a.isPrefixed then s"${a.pre}:${a.key}" else a.key
    }

    attributes1
      .zip(refAttributes1)
      .foldLeft(Some(Map()): Option[Map[Int, Any]]) {
        case (Some(map), (attr, refAttr)) =>
          for fromNodes <- findInNodes(attr.value, refAttr.value)
          yield map ++ fromNodes
        case _ =>
          return None
      }
  }

  private def findInText(text: Text, refText: scala.xml.Text): Option[Map[Int, Any]] =
    if text.text == refText.text then
      Some(Map())
    else
      None

//  private def expandComment(comment: Comment)(using Quotes): Expr[scala.xml.Comment] =
//    '{ new _root_.scala.xml.Comment(${Expr(comment.text)}) }

  private def fillPlaceholder(placeholder: Placeholder, data: scala.xml.Node): Option[Map[Int, Any]] =
    Some(Map(placeholder.id -> data))

  private def findInPCData(pcdata: PCData, refPCData: scala.xml.PCData): Option[Map[Int, Any]] =
    if pcdata.data == refPCData.data then Some(Map()) else None

  private def findInProcInstr(instr: ProcInstr, refInstr: scala.xml.ProcInstr): Option[Map[Int, Any]] =
    if instr.target == refInstr.target then Some(Map()) else None

  private def findInEntityRef(ref: EntityRef, refRef: scala.xml.EntityRef): Option[Map[Int, Any]] =
    if ref.name == refRef.entityName then Some(Map()) else None

  private def findInUnparsed(unparsed: Unparsed, refUnparsed: scala.xml.Unparsed): Option[Map[Int, Any]] =
    if unparsed.data == refUnparsed.data then Some(Map()) else None
}
