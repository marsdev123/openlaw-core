package org.adridadou.openlaw.parser.template

import cats.kernel.Eq
import cats.implicits._
import io.circe._
import io.circe.generic.auto._
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.syntax._
import org.adridadou.openlaw.OpenlawNativeValue
import org.adridadou.openlaw.parser.template.variableTypes._
import org.parboiled2.Rule1
import org.adridadou.openlaw.parser.template.expressions._
import org.adridadou.openlaw.result.Result

import scala.reflect.ClassTag

/**
  * Created by davidroon on 06.06.17.
  */
trait ExpressionRules extends JsonRules {

  def ExpressionRule:Rule1[Expression] = rule {
    BooleanTerm | Term | SubTerm | Factor
  }

  def BooleanTerm: Rule1[Expression] = rule {
    Term ~ wsNoReturn ~ zeroOrMore(BooleanPartial) ~> ((left:Expression, others:Seq[PartialOperation]) => others.foldLeft(left)({
      case (current, PartialOperation(op, right)) => createOperation(current, op, right)
    }))
  }

  def BooleanPartial: Rule1[PartialOperation] = rule {
    booleanOperation ~ wsNoReturn ~ (Term | Factor) ~> ((op:String, expr:Expression) => PartialOperation(op, expr))
  }

  def ValuePartial: Rule1[PartialOperation] = rule {
    valueOperation ~ wsNoReturn ~ (SubTerm | Factor) ~> ((op:String, expr:Expression) => PartialOperation(op, expr))
  }

  def Term: Rule1[Expression] = rule {
    SubTerm ~ wsNoReturn ~ comparisonOperation ~ wsNoReturn ~ SubTerm ~> ((left:Expression, op:String, right: Expression) => createOperation(left, op, right))
  }

  def SubTerm: Rule1[Expression] = rule {
    Factor ~ wsNoReturn ~ zeroOrMore(ValuePartial) ~> ((left:Expression, others:Seq[PartialOperation]) => others.foldLeft(left)({
      case (left, PartialOperation(op, right)) => createOperation(left, op, right)
    })) |
    Factor
  }

  private def createOperation(left:Expression, op:String, right:Expression): Expression = op match {
    case "+" => ValueExpression(left, right, Plus)
    case "-" => ValueExpression(left, right, Minus)
    case "/" => ValueExpression(left, right, Divide)
    case "*" => ValueExpression(left, right, Multiple)
    case "||" => BooleanExpression(left, right, Or)
    case "&&" => BooleanExpression(left, right, And)
    case ">" => ComparisonExpression(left, right, GreaterThan)
    case "<" => ComparisonExpression(left, right, LesserThan)
    case ">=" => ComparisonExpression(left, right, GreaterOrEqual)
    case "<=" => ComparisonExpression(left, right, LesserOrEqual)
    case "=" => ComparisonExpression(left, right, Equals)
    case _ => throw new RuntimeException(s"unknown operation ${op}")
  }

  def  Factor:Rule1[Expression] = rule {constant | conditionalVariableDefinition | variableMemberInner | variableName | Parens | UnaryMinus | UnaryNot }

  def Parens:Rule1[Expression] = rule { '(' ~ wsNoReturn ~ ExpressionRule ~ wsNoReturn ~ ')' ~> ((expr:Expression) => ParensExpression(expr)) }

  def UnaryMinus:Rule1[Expression] = rule { '-' ~ wsNoReturn ~ ExpressionRule ~> ((expr: Expression) => ValueExpression(NumberConstant(BigDecimal(-1)), expr, Multiple))}

  def UnaryNot:Rule1[Expression] = rule { '!' ~ wsNoReturn ~ ExpressionRule ~> ((expr: Expression) => BooleanUnaryExpression(expr, Not))}

  private def valueOperation:Rule1[String] = rule {
    capture("+" | "-" | "/" | "*")
  }

  private def comparisonOperation:Rule1[String] = rule {
    capture(">=" | "<=" | ">" | "<" | "=" | "||" | "&&")
  }

  private def booleanOperation:Rule1[String] = rule {
    capture("||" | "&&" | "=")
  }

  private def operation:Rule1[String] = rule {
    capture("+" | "-" | "/" | "*" | ">=" | "<=" | ">" | "<" | "=" | "||" | "&&")
  }

  def variableAlias : Rule1[VariableAliasing] = rule { openS  ~ wsNoReturn ~ variableAliasingDefinition ~ wsNoReturn ~ closeS}

  def varAliasKey: Rule1[VariableAliasing] = rule { &(openS) ~ variableAlias  }

  def variableAliasingDefinition:Rule1[VariableAliasing] = rule {
    "@" ~ charsKeyAST ~ zeroOrMore(' ')  ~ "=" ~ wsNoReturn ~ ExpressionRule ~>
      ((aKey:String, expression:Expression) => {
        VariableAliasing(VariableName(aKey.trim), expression)
      })
  }

  def variableName:Rule1[VariableName] = rule {
    charsKeyAST ~> ((name:String) => VariableName(name.trim))
  }

  def conditionalVariableDefinition:Rule1[VariableDefinition] = rule {
    variableName ~ ws ~ stringDefinition ~> ((variableName:VariableName, description:String) => VariableDefinition(variableName, Some(VariableTypeDefinition(YesNoType.name)), Some(description)))
  }

  def formatterDefinition:Rule1[FormatterDefinition] = rule {
    charsKeyAST ~ optional("(" ~ ws ~ parametersDefinition ~ ws ~ ")") ~> ((name:String, parameters:Option[Parameter]) => FormatterDefinition(name, parameters))
  }

  def variableDefinition:Rule1[VariableDefinition] = rule {
    optional(capture("#")) ~
      variableName ~
      optional(variableTypeDefinition) ~ ws ~
      optional("|" ~ ws ~ formatterDefinition)  ~ ws ~
      optional(stringDefinition) ~>
      ((prefix:Option[String], name:VariableName, optVarType:Option[(VariableTypeDefinition, Option[Parameter])], formatter:Option[FormatterDefinition], desc:Option[String]) => {
        val varTypeDefinition = optVarType.map({ case (variableType, _) => variableType})
        val optParams = optVarType.flatMap({case(_, ordered) => ordered})

        VariableDefinition(name, varTypeDefinition , desc.map(_.trim), formatter, prefix.isDefined, optParams)
      })
  }

  def variableTypeDefinition:Rule1[(VariableTypeDefinition, Option[Parameter])] = rule {
    ":" ~ variableType ~ optional("(" ~ ws ~ parametersDefinition ~ ws ~ ")") ~> ((varType:VariableTypeDefinition, params:Option[Parameter]) => (varType, params))
  }

  def variableType: Rule1[VariableTypeDefinition] = rule {
    capture(oneOrMore(keyChar)) ~ optional("<" ~ variableType ~ ">") ~> ((s: String, optTypeParameter:Option[VariableTypeDefinition]) => VariableTypeDefinition(s.trim, optTypeParameter))
  }

  def variable : Rule1[VariableDefinition] = rule {
    openS ~ wsNoReturn ~ variableDefinition ~ wsNoReturn ~ closeS
  }

  def variableMember: Rule1[VariableMember] = rule {
    openS ~ wsNoReturn ~ charsKeyAST ~ oneOrMore("." ~ charsKeyAST) ~ optional( ws ~ "|" ~ formatterDefinition) ~ closeS ~>((name:String, member:Seq[String], formatter:Option[FormatterDefinition]) => VariableMember(VariableName(name), member.map(_.trim), formatter))
  }

  def variableMemberInner: Rule1[VariableMember] = rule {
    charsKeyAST ~ oneOrMore("." ~ charsKeyAST) ~>((name:String, member:Seq[String]) => VariableMember(VariableName(name),member.map(_.trim), None))
  }

  def varKey: Rule1[VariableDefinition] = rule { &(openS) ~ variable }

  def varMemberKey: Rule1[VariableMember] = rule { &(openS) ~  variableMember}

  def parametersDefinition:Rule1[Parameter] = rule {
    parametersMapDefinition |
    (oneOrMore(ws ~ ExpressionRule ~ ws).separatedBy(",") ~> {
     s: Seq[Expression] =>
      s.toList match {
        // the typical match for Seq() triggers a compiler bug, so this is a workaround
        case head::Nil => OneValueParameter(head)
        case lst => ListParameter(lst)
      }
    })
  }

  def parametersMapDefinition:Rule1[Parameters] = rule {
    oneOrMore(ws ~ charsKeyAST ~ ws ~ ":" ~ ws ~ (MappingParameterEntry | parameterEntry) ~ ws ~> ((key,value) => key -> value))
      .separatedBy(";") ~> ((values:Seq[(String, Parameter)]) => Parameters(values.toList))
  }

  def parameterEntry:Rule1[Parameter] = rule {
    oneOrMore(ExpressionRule).separatedBy(',') ~> ((n:Seq[Expression]) => n.toList match {
      case name::Nil => OneValueParameter(name)
      case names => ListParameter(names.map(v => v))
    })
  }

  def MappingParameterEntry:Rule1[MappingParameter] = rule {
    oneOrMore(MappingRule).separatedBy(',') ~> ((n:Seq[(VariableName, Expression)]) => {
      val mappingInternal = n.map({case (n,v) => n.name -> v}).toMap
      MappingParameter(mappingInternal)
    })
  }

  def MappingRule:Rule1[(VariableName, Expression)] = rule {
    ws ~ charsKeyAST ~ wsNoReturn ~ "->" ~ wsNoReturn ~ ExpressionRule ~ wsNoReturn ~> ((name:String, expr:Expression) => (VariableName(name.trim), expr))
  }

  def constant:Rule1[Expression] = rule {
    ws ~ (
    numberDefinition ~> ((constant:BigDecimal) => NumberConstant(constant)) |
    stringDefinition ~> ((constant:String) => StringConstant(constant)) |
    jsonDefinition ~> ((json:Json) => JsonConstant(json.noSpaces)))
  }
}
sealed trait Operation

sealed trait BooleanOperation
sealed trait BooleanUnaryOperation {
  def toString(expr:Expression):String
}

case object GreaterThan extends Operation {
  override def toString: String = ">"
}
case object LesserThan extends Operation {
  override def toString: String = "<"
}
case object GreaterOrEqual extends Operation {
  override def toString: String = ">="
}
case object LesserOrEqual extends Operation {
  override def toString: String = "<="
}
case object Equals extends Operation {
  override def toString: String = "="
}


case object And extends BooleanOperation {
  override def toString: String = "&&"
}
case object Or extends BooleanOperation {
  override def toString: String = "||"
}
case object Not extends BooleanUnaryOperation {
  override def toString(expr: Expression): String = "!(" + expr.toString + ")"
}

sealed trait ValueOperation

case object Plus extends ValueOperation {
  override def toString: String = "+"
}
case object Minus extends ValueOperation{
  override def toString: String = "-"
}
case object Multiple extends ValueOperation{
  override def toString: String = "*"
}
case object Divide extends ValueOperation{
  override def toString: String = "/"
}

case object Compare extends ValueOperation

object Parameter {
  implicit val parameterEq:Eq[Parameter] = Eq.fromUniversalEquals

  implicit val parameterEnc:Encoder[Parameter] = (a: Parameter) => {
    Json.obj(
      "name" -> Json.fromString(a.getClass.getSimpleName),
      "value" -> a.serialize
    )
  }
  implicit val parameterDec:Decoder[Parameter] = (c: HCursor) => {
    c.downField("name").as[String].flatMap(decodeParameter(_, c))
  }

  private def className[T](implicit classTag: ClassTag[T]):String = classTag.runtimeClass.getSimpleName

  private def decodeParameter(name: String, cursor: HCursor):Decoder.Result[Parameter] = {
    name match {
      case _ if name === className[OneValueParameter] =>
        cursor.downField("value").as[OneValueParameter]
      case _ if name === className[ListParameter] =>
        cursor.downField("value").as[ListParameter]
      case _ if name === className[Parameters] =>
        cursor.downField("value").as[Parameters]
      case _ if name === className[MappingParameter] =>
        cursor.downField("value").as[MappingParameter]
      case _ => Left(DecodingFailure(s"unknown parameter type $name", List()))
    }
  }
}

sealed trait Parameter extends OpenlawNativeValue {
  def serialize:Json
  def variables(executionResult: TemplateExecutionResult): Result[Seq[VariableName]]
}
final case class OneValueParameter(expr:Expression) extends Parameter {
  override def variables(executionResult: TemplateExecutionResult): Result[Seq[VariableName]] =
    expr.variables(executionResult)

  override def serialize: Json = this.asJson
}
final case class ListParameter(exprs:Seq[Expression]) extends Parameter {
  override def variables(executionResult: TemplateExecutionResult): Result[Seq[VariableName]] =
    exprs.toList.map(_.variables(executionResult)).sequence.map(_.flatten.distinct)

  override def serialize: Json = this.asJson
}

final case class Parameters(parameterMap:List[(String, Parameter)]) extends Parameter {
  override def variables(executionResult: TemplateExecutionResult): Result[Seq[VariableName]] =
    parameterMap.map { case (_,param) => param.variables(executionResult) }.sequence.map(_.flatten.distinct)

  override def serialize: Json = this.asJson
}

final case class MappingParameter(mappingInternal: Map[String, Expression]) extends Parameter {
  def mapping:Map[VariableName, Expression] = mappingInternal.map({case (key,value) => VariableName(key) -> value})
  override def variables(executionResult: TemplateExecutionResult): Result[Seq[VariableName]] =
    mapping.values.toList.map(_.variables(executionResult)).sequence.map(_.flatten.distinct)

  override def serialize: Json = this.asJson
}

object VariableTypeDefinition {
  implicit val variableTypeDefinitionEq:Eq[VariableTypeDefinition] = Eq.fromUniversalEquals
  implicit val variableTypeDefinitionEnc:Encoder[VariableTypeDefinition] = deriveEncoder
  implicit val variableTypeDefinitionDec:Decoder[VariableTypeDefinition] = deriveDecoder
}

final case class VariableTypeDefinition(name:String, typeParameter:Option[VariableTypeDefinition] = None)

final case class PartialOperation(op:String, expr:Expression)
