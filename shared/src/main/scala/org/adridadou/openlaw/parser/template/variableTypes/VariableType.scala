package org.adridadou.openlaw.parser.template.variableTypes

import java.time.Instant

import org.adridadou.openlaw.parser.template._
import org.adridadou.openlaw.parser.template.expressions.{
  Expression,
  ValueExpression
}
import org.adridadou.openlaw.parser.template.formatters.{
  DefaultFormatter,
  Formatter
}
import cats.Eq
import cats.implicits._
import io.circe._
import io.circe.syntax._
import io.circe.generic.semiauto._

import scala.reflect.ClassTag
import scala.util.Try
import cats.data.EitherT
import org.adridadou.openlaw.result.{Failure, Result, Success}
import LocalDateTimeHelper._
import org.adridadou.openlaw._
import org.adridadou.openlaw.oracles.{
  EthereumEventFilterExecution,
  PreparedERC712SmartContractCallExecution
}
import org.adridadou.openlaw.vm.Executions

trait NoShowInForm

trait NoShowInFormButRender extends NoShowInForm

trait ActionValue {
  def nextActionSchedule(
      executionResult: TemplateExecutionResult,
      pastExecutions: List[OpenlawExecution]
  ): Result[Option[Instant]]
  def identifier(
      executionResult: TemplateExecutionResult
  ): Result[ActionIdentifier]
  def executions(
      executionResult: TemplateExecutionResult
  ): Result[Option[Executions]] =
    identifier(executionResult).map(executionResult.executions.get(_))
}

trait ActionType extends NoShowInForm {
  def actionValue(value: OpenlawValue): Result[ActionValue]
}

object OpenlawExecution {
  implicit val openlawExecutionEnc: Encoder[OpenlawExecution] =
    (a: OpenlawExecution) =>
      Json.obj(
        "type" -> Json.fromString(a.typeIdentifier),
        "value" -> a.serialize
      )
  implicit val openlawExecutionDec: Decoder[OpenlawExecution] = (c: HCursor) =>
    c.downField("type")
      .as[String]
      .flatMap(convertOpenlawExecution(_, c.downField("value")))

  protected def className[T]()(implicit cls: ClassTag[T]): String =
    cls.runtimeClass.getName

  private def convertOpenlawExecution(
      typeDefinition: String,
      cursor: ACursor
  ): Decoder.Result[OpenlawExecution] = typeDefinition match {
    case _ if typeDefinition === className[EthereumEventFilterExecution] =>
      cursor.as[EthereumEventFilterExecution]
    case _ if typeDefinition === className[EthereumSmartContractExecution] =>
      cursor.as[EthereumSmartContractExecution]
    case _ if typeDefinition === className[SuccessfulExternalCallExecution] =>
      cursor.as[SuccessfulExternalCallExecution]
    case _ if typeDefinition === className[FailedExternalCallExecution] =>
      cursor.as[FailedExternalCallExecution]
    case _ if typeDefinition === className[PendingExternalCallExecution] =>
      cursor.as[PendingExternalCallExecution]
  }
}

object OpenlawExecutionInit {
  implicit val openlawExecutionInitEnc: Encoder[OpenlawExecutionInit] =
    (a: OpenlawExecutionInit) =>
      Json.obj(
        "type" -> Json.fromString(a.typeIdentifier),
        "value" -> Json.fromString(a.serialize)
      )
  implicit val openlawExecutionDec: Decoder[OpenlawExecutionInit] =
    (c: HCursor) =>
      c.downField("type")
        .as[String]
        .flatMap(convertOpenlawExecutionInit(_, c.downField("value")))

  protected def className[T]()(implicit cls: ClassTag[T]): String =
    cls.runtimeClass.getName

  private def convertOpenlawExecutionInit(
      typeDefinition: String,
      cursor: ACursor
  ): Decoder.Result[OpenlawExecutionInit] = typeDefinition match {
    case _
        if typeDefinition === className[
          PreparedERC712SmartContractCallExecution
        ] =>
      cursor.as[PreparedERC712SmartContractCallExecution]
  }
}

trait OpenlawExecutionInit extends OpenlawNativeValue {
  protected def className[T]()(implicit cls: ClassTag[T]): String =
    cls.runtimeClass.getName

  def typeIdentifier: String
  def serialize: String
}

trait OpenlawExecution extends OpenlawNativeValue {
  def scheduledDate: Instant
  def executionDate: Instant
  def executionStatus: OpenlawExecutionStatus
  def key: Any
  def typeIdentifier: String
  def serialize: Json
  protected def className[T]()(implicit cls: ClassTag[T]): String =
    cls.runtimeClass.getName
}

object EthereumSmartContractExecution {
  implicit val smartContractExecutionEnc
      : Encoder[EthereumSmartContractExecution] = deriveEncoder
  implicit val smartContractExecutionDec
      : Decoder[EthereumSmartContractExecution] = deriveDecoder
}

final case class EthereumSmartContractExecution(
    scheduledDate: Instant,
    executionDate: Instant,
    executionStatus: OpenlawExecutionStatus = PendingExecution,
    tx: EthereumHash
) extends OpenlawExecution {
  def message: String = executionStatus match {
    case PendingExecution =>
      "the transaction has been submitted, waiting for the transaction to be executed"
    case SuccessfulExecution =>
      "the transaction has been added to the chain and successfully executed"
    case FailedExecution => "the transaction execution has failed"
  }

  def key: EthereumHash = tx

  override def typeIdentifier: String =
    className[EthereumSmartContractExecution]
  override def serialize: Json = this.asJson
}

case object RequestIdentifier {
  implicit val requestIdentifierEnc: Encoder[RequestIdentifier] =
    (a: RequestIdentifier) => Json.fromString(a.identifier)
  implicit val requestIdentifierDec: Decoder[RequestIdentifier] =
    (c: HCursor) => c.as[String].map(RequestIdentifier(_))

  implicit val requestIdentifierEq: Eq[RequestIdentifier] =
    Eq.fromUniversalEquals
}

final case class RequestIdentifier(identifier: String)

object SuccessfulExternalCallExecution {
  implicit val successfulExternalCallExecutionEnc
      : Encoder[SuccessfulExternalCallExecution] = deriveEncoder
  implicit val successfulExternalCallExecutionDec
      : Decoder[SuccessfulExternalCallExecution] = deriveDecoder
}

final case class SuccessfulExternalCallExecution(
    scheduledDate: Instant,
    executionDate: Instant,
    result: String,
    requestIdentifier: RequestIdentifier
) extends ExternalCallExecution {
  def message: String =
    "the request has been added to the integrator queue and successfully executed"

  override def typeIdentifier: String =
    className[SuccessfulExternalCallExecution]
  override def serialize: Json = this.asJson
  override def executionStatus: OpenlawExecutionStatus = SuccessfulExecution
}

object PendingExternalCallExecution {
  implicit val pendingExternalCallExecutionEnc
      : Encoder[PendingExternalCallExecution] = deriveEncoder
  implicit val pendingExternalCallExecutionDec
      : Decoder[PendingExternalCallExecution] = deriveDecoder
}

final case class PendingExternalCallExecution(
    scheduledDate: Instant,
    executionDate: Instant,
    requestIdentifier: RequestIdentifier,
    actionIdentifier: Option[ActionIdentifier] = None
) extends ExternalCallExecution {
  def message: String =
    "the request has been submitted, waiting for the request to be executed"
  override def typeIdentifier: String = className[PendingExternalCallExecution]
  override def serialize: Json = this.asJson
  override def executionStatus: OpenlawExecutionStatus = PendingExecution
}

object FailedExternalCallExecution {
  implicit val failedExternalCallExecutionEnc
      : Encoder[FailedExternalCallExecution] = deriveEncoder
  implicit val failedExternalCallExecutionDec
      : Decoder[FailedExternalCallExecution] = deriveDecoder
}

final case class FailedExternalCallExecution(
    scheduledDate: Instant,
    executionDate: Instant,
    errorMessage: String,
    requestIdentifier: RequestIdentifier
) extends ExternalCallExecution {
  def message: String = s"the request execution has failed. $errorMessage"
  override def typeIdentifier: String = className[FailedExternalCallExecution]
  override def serialize: Json = this.asJson
  override def executionStatus: OpenlawExecutionStatus = FailedExecution
}

sealed trait ExternalCallExecution extends OpenlawExecution {
  val requestIdentifier: RequestIdentifier

  def key: String = requestIdentifier.identifier
}

sealed abstract class OpenlawExecutionStatus(val name: String)

case object PendingExecution extends OpenlawExecutionStatus("pending")
case object SuccessfulExecution extends OpenlawExecutionStatus("success")
case object FailedExecution extends OpenlawExecutionStatus("failed")

object OpenlawExecutionStatus {

  def apply(name: String): OpenlawExecutionStatus = name match {
    case "success" => SuccessfulExecution
    case "failed"  => FailedExecution
    case _         => PendingExecution
  }

  implicit val executionStatusDecoder: Decoder[OpenlawExecutionStatus] =
    (c: HCursor) =>
      for {
        name <- c.as[String]
      } yield OpenlawExecutionStatus(name)

  implicit val executionStatusEncoder: Encoder[OpenlawExecutionStatus] =
    (a: OpenlawExecutionStatus) => Json.fromString(a.name)

  implicit val eqForExecutionStatus: Eq[OpenlawExecutionStatus] =
    Eq.fromUniversalEquals
}

trait ParameterType {
  val typeParameter: VariableType
}

trait ParameterTypeProvider {
  def createParameterInstance(
      parameterType: VariableType
  ): VariableType with ParameterType
}

abstract class VariableType(val name: String) extends OpenlawNativeValue {
  def serialize: Json = Json.obj("name" -> io.circe.Json.fromString(name))

  def validateOperation(
      expr: ValueExpression,
      executionResult: TemplateExecutionResult
  ): Result[Unit] = Success(())

  def accessVariables(
      name: VariableName,
      keys: List[VariableMemberKey],
      executionResult: TemplateExecutionResult
  ): Result[List[VariableName]] =
    Success(List(name))

  def operationWith(
      rightType: VariableType,
      operation: ValueOperation
  ): VariableType =
    if (thisType === TextType || rightType === TextType) {
      TextType
    } else {
      this
    }

  def access(
      value: OpenlawValue,
      variableName: VariableName,
      keys: List[VariableMemberKey],
      executionResult: TemplateExecutionResult
  ): Result[Option[OpenlawValue]] =
    if (keys.isEmpty) {
      Success(Some(value))
    } else {
      Failure(s"The variable $variableName of type $name has no properties")
    }

  def getTypeClass: Class[_ <: OpenlawValue]

  def typeNames: List[String] =
    List(this.name)

  def validateKeys(
      variableName: VariableName,
      keys: List[VariableMemberKey],
      expression: Expression,
      executionResult: TemplateExecutionResult
  ): Result[Unit] =
    keys.headOption
      .map(_ =>
        Failure(s"The variable $variableName of type $name has no properties")
      )
      .getOrElse(Success.unit)

  def keysType(
      keys: List[VariableMemberKey],
      expression: Expression,
      executionResult: TemplateExecutionResult
  ): Result[VariableType] =
    if (keys.nonEmpty) {
      Failure(
        s"the type $name has no properties (tried to access ${keys.mkString(".")})"
      )
    } else {
      Success(thisType)
    }

  def combineConverted[U <: OpenlawValue, Y <: OpenlawValue](
      optLeft: Option[OpenlawValue],
      optRight: Option[OpenlawValue]
  )(
      operation: PartialFunction[(U#T, U#T), Result[Y]]
  )(implicit ct: ClassTag[U]): Result[Option[Y]] =
    combineConverted[U, U, Y](optLeft, optRight)(operation)

  def combineConverted[U <: OpenlawValue, V <: OpenlawValue, Y <: OpenlawValue](
      optLeft: Option[OpenlawValue],
      optRight: Option[OpenlawValue]
  )(
      operation: PartialFunction[(U#T, V#T), Result[Y]]
  )(implicit ct1: ClassTag[U], ct2: ClassTag[V]): Result[Option[Y]] = {
    (for {
      left <- EitherT(optLeft.map(VariableType.convert[U]))
      right <- EitherT(optRight.map(VariableType.convert[V]))
    } yield {
      if (operation.isDefinedAt(left -> right)) operation(left -> right)
      else
        Failure(
          s"no matching case in partial function for arguments $left and $right"
        )
    }).value
      .map(_.flatten)
      .sequence
  }

  def combine[Y <: OpenlawValue](
      optLeft: Option[OpenlawValue],
      optRight: Option[OpenlawValue]
  )(
      operation: PartialFunction[(OpenlawValue, OpenlawValue), Result[Y]]
  ): Result[Option[Y]] = {
    (for {
      left <- optLeft
      right <- optRight
    } yield {
      if (operation.isDefinedAt(left -> right)) operation(left -> right)
      else
        Failure(
          s"no matching case in partial function for arguments $left and $right"
        )
    }).sequence
  }

  def plus(
      left: Expression,
      right: Expression,
      executionResult: TemplateExecutionResult
  ): Result[Option[OpenlawValue]] =
    Failure(
      new UnsupportedOperationException(s"$name type does not support addition")
    )
  def minus(
      left: Expression,
      right: Expression,
      executionResult: TemplateExecutionResult
  ): Result[Option[OpenlawValue]] =
    Failure(
      new UnsupportedOperationException(
        s"$name type does not support substraction"
      )
    )
  def multiply(
      left: Expression,
      right: Expression,
      executionResult: TemplateExecutionResult
  ): Result[Option[OpenlawValue]] =
    Failure(
      new UnsupportedOperationException(
        s"$name type does not support multiplication"
      )
    )
  def divide(
      left: Expression,
      right: Expression,
      executionResult: TemplateExecutionResult
  ): Result[Option[OpenlawValue]] =
    Failure(
      new UnsupportedOperationException(s"$name type does not support division")
    )

  def isCompatibleType(
      otherType: VariableType,
      operation: ValueOperation
  ): Boolean =
    otherType === this

  def cast(
      value: String,
      executionResult: TemplateExecutionResult
  ): Result[OpenlawValue]

  def internalFormat(value: OpenlawValue): Result[String]

  def construct(
      constructorParams: Parameter,
      executionResult: TemplateExecutionResult
  ): Result[Option[OpenlawValue]] = constructorParams match {
    case OneValueParameter(expr) =>
      expr.evaluate(executionResult)
    case Parameters(parameterMap) =>
      parameterMap.toMap.get("value") match {
        case Some(parameter) =>
          construct(parameter, executionResult)
        case None => Success(None)
      }
    case _ =>
      Failure(s"the constructor for $name only handles single values")
  }

  def defaultFormatter: Formatter =
    DefaultFormatter

  def getFormatter(
      name: FormatterDefinition,
      executionResult: TemplateExecutionResult
  ): Result[Formatter] = Success(defaultFormatter)

  def getSingleParameter(constructorParams: Parameter): Result[Expression] =
    constructorParams match {
      case OneValueParameter(expr) => Success(expr)
      case _ =>
        Failure("expecting a single value")
    }

  def handleTry[T](thisTry: Try[T]): T =
    thisTry match {
      case scala.util.Success(v)  => v
      case scala.util.Failure(ex) => throw ex
    }

  def thisType: VariableType

  def getExpression(
      params: Map[String, Parameter],
      names: String*
  ): Result[Expression] =
    getParameter(params, names: _*)
      .map(getExpression)
      .sequence
      .flatMap {
        case Some(expr) => Success(expr)
        case None =>
          Failure(
            s"parameter $name not found. available parameters: ${params.keys.mkString(",")}"
          )
      }

  def getParameter(
      params: Map[String, Parameter],
      names: String*
  ): Option[Parameter] =
    names.flatMap(params.get).headOption

  def getExpression(param: Parameter): Result[Expression] = param match {
    case OneValueParameter(expr) => Success(expr)
    case _ =>
      Failure(
        "invalid parameter type " + param.getClass.getSimpleName + " expecting single expression"
      )
  }

  def getMandatoryParameter(
      name: String,
      parameter: Parameters
  ): Result[Parameter] = {
    parameter.parameterMap.toMap.get(name) match {
      case Some(param) => Success(param)
      case None        => Failure(s"mandatory parameter $name could not be found")
    }
  }
}

object VariableTypeType extends VariableType("VariableType") {
  override def getTypeClass: Class[_ <: OpenlawValue] = classOf[VariableType]
  override def cast(
      value: String,
      executionResult: TemplateExecutionResult
  ): Result[OpenlawValue] = Failure("you cannot cast a variable type!")
  override def internalFormat(value: OpenlawValue): Result[String] =
    VariableType.convert[VariableType](value).map(varType => varType.name)

  override def thisType: VariableType = VariableTypeType
}

object VariableType {

  val allTypes: List[VariableType] =
    List(
      AbstractCollectionType,
      AbstractFunctionType,
      OLOwnType,
      AddressType,
      ChoiceType,
      ClauseType,
      DateType,
      DateTimeType,
      AbstractDomainType,
      EthAddressType,
      EthTxHashType,
      EthereumCallType,
      EthereumEventFilterType,
      ExternalCallType,
      ExternalSignatureType,
      ExternalStorageType,
      IdentityType,
      LargeTextType,
      LinkType,
      ImageType,
      NumberType,
      PeriodType,
      SectionType,
      SmartContractMetadataType,
      AbstractStructureType,
      TextType,
      ValidationType,
      RegexType,
      YesNoType
    )

  val allTypesMap: Map[String, VariableType] = allTypes
    .flatMap(varType => varType.typeNames.map(name => name -> varType))
    .toMap

  def getByName(name: String): Option[VariableType] =
    allTypesMap.get(name)

  implicit val eqForVariableType: Eq[VariableType] =
    (x: VariableType, y: VariableType) => x == y

  def getPeriod(
      v: Expression,
      executionResult: TemplateExecutionResult
  ): Result[Period] =
    get(v, executionResult, PeriodType.cast)
  def getEthereumAddress(
      v: Expression,
      executionResult: TemplateExecutionResult
  ): Result[EthereumAddress] =
    get(v, executionResult, EthAddressType.cast)
  def getDate(
      v: Expression,
      executionResult: TemplateExecutionResult
  ): Result[OpenlawInstant] =
    get(v, executionResult, DateTimeType.cast)
  def getMetadata(
      v: Expression,
      executionResult: TemplateExecutionResult
  ): Result[SmartContractMetadata] =
    get(v, executionResult, SmartContractMetadataType.cast)
  def getString(
      v: Expression,
      executionResult: TemplateExecutionResult
  ): Result[String] =
    get[OpenlawString](v, executionResult, (str, _) => Success(str))
      .map(_.underlying)

  def get[T <: OpenlawValue](
      expr: Expression,
      executionResult: TemplateExecutionResult,
      cast: (String, TemplateExecutionResult) => Result[T]
  )(implicit classTag: ClassTag[T]): Result[T] =
    expr
      .evaluate(executionResult)
      .flatMap {
        case Some(value: T) => Success(value)
        case Some(value: OpenlawString) =>
          cast(value.underlying, executionResult)
        case Some(value) =>
          Failure(
            "cannot get value of type " + value.getClass.getSimpleName + ". expecting " + classTag.runtimeClass.getSimpleName
          )
        case None => Failure("could not get the value. Missing data")
      }

  def convert[U <: OpenlawValue](
      value: OpenlawValue
  )(implicit classTag: ClassTag[U]): Result[U#T] = value match {
    case convertedValue: U =>
      Success(convertedValue.underlying)
    case other =>
      val msg = "invalid type " +
        other.getClass.getSimpleName +
        " expecting " +
        classTag.runtimeClass.getSimpleName +
        s".value:$other"
      Failure(msg)
  }

  implicit val variableTypeEnc: Encoder[VariableType] = (a: VariableType) =>
    a.serialize

  implicit val variableTypeDec: Decoder[VariableType] = (c: HCursor) =>
    for {
      name <- c.downField("name").as[String]
      result <- VariableType.allTypesMap.get(name) match {
        case Some(varType) => Right(varType)
        case None          => createCustomType(c, name)
      }
    } yield result

  private def createCustomType(
      cursor: HCursor,
      name: String
  ): Decoder.Result[VariableType] =
    DefinedDomainType.definedDomainTypeDec(cursor) orElse
      DefinedStructureType.definedStructureTypeDec(cursor) orElse
      DefinedChoiceType.definedChoiceTypeDec(cursor) orElse
      Left(DecodingFailure(s"unknown type $name. or error while decoding", Nil))

}

object LocalDateTimeHelper {
  implicit val instantDecoder: Decoder[Instant] = (c: HCursor) => {
    for {
      epoch <- c.as[Long]
    } yield Instant.ofEpochSecond(epoch)
  }

  implicit val instantEncoder: Encoder[Instant] = (a: Instant) =>
    Json.fromLong(a.getEpochSecond)

  implicit val eqForInstant: Eq[Instant] = Eq.fromUniversalEquals

  implicit val instantKeyEncoder: KeyEncoder[Instant] =
    (key: Instant) => key.getEpochSecond.toString

  implicit val instantKeyDecoder: KeyDecoder[Instant] = (key: String) =>
    Try(Instant.ofEpochSecond(key.toLong)).toOption
}
