/*
 * Copyright 2021 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.gform.sharedmodel.formtemplate

import cats.Eq
import julienrf.json.derived
import play.api.libs.json._
import uk.gov.hmrc.gform.models.{ FormModel, PageMode }
import uk.gov.hmrc.gform.models.Atom
import uk.gov.hmrc.gform.sharedmodel.{ DataRetrieveAttribute, DataRetrieveId }

sealed trait Expr extends Product with Serializable {

  def firstExprForTypeResolution[T <: PageMode](formModel: FormModel[T]): Option[Expr] = {
    def loop(expr: Expr): List[Expr] = expr match {
      case Add(field1: Expr, field2: Expr)         => loop(field1) ++ loop(field2)
      case Multiply(field1: Expr, field2: Expr)    => loop(field1) ++ loop(field2)
      case Subtraction(field1: Expr, field2: Expr) => loop(field1) ++ loop(field2)
      case IfElse(_, field1: Expr, field2: Expr)   => loop(field1) ++ loop(field2)
      case Else(field1: Expr, field2: Expr)        => loop(field1) ++ loop(field2)
      case FormCtx(_)                              => expr :: Nil
      case Sum(field1: Expr) =>
        field1 match {
          case FormCtx(formComponentId) =>
            formModel.allFormComponents.collect {
              case fc if fc.baseComponentId == formComponentId.baseComponentId => FormCtx(fc.id)
            }
          case _ => loop(field1)
        }
      case Count(formComponentId: FormComponentId) => FormCtx(formComponentId.withFirstIndex) :: Nil
      case AuthCtx(_)                              => expr :: Nil
      case UserCtx(_)                              => expr :: Nil
      case Constant(_)                             => expr :: Nil
      case PeriodValue(_)                          => expr :: Nil
      case HmrcRosmRegistrationCheck(_)            => expr :: Nil
      case Value                                   => expr :: Nil
      case FormTemplateCtx(_)                      => expr :: Nil
      case ParamCtx(_)                             => expr :: Nil
      case LinkCtx(_)                              => expr :: Nil
      case LangCtx                                 => expr :: Nil
      case DateCtx(dateExpr)                       => dateExpr.leafExprs
      case Period(_, _)                            => expr :: Nil
      case PeriodExt(_, _)                         => expr :: Nil
      case AddressLens(_, _)                       => expr :: Nil
      case DataRetrieveCtx(_, _)                   => expr :: Nil
    }
    loop(this).headOption
  }

  def leafs[T <: PageMode](formModel: FormModel[T]): List[Expr] = this match {
    case Add(field1: Expr, field2: Expr)         => field1.leafs(formModel) ++ field2.leafs(formModel)
    case Multiply(field1: Expr, field2: Expr)    => field1.leafs(formModel) ++ field2.leafs(formModel)
    case Subtraction(field1: Expr, field2: Expr) => field1.leafs(formModel) ++ field2.leafs(formModel)
    case IfElse(cond, field1: Expr, field2: Expr) =>
      cond.allExpressions.flatMap(_.leafs(formModel)) ++
        field1.leafs(formModel) ++ field2.leafs(formModel)
    case Else(field1: Expr, field2: Expr)          => field1.leafs(formModel) ++ field2.leafs(formModel)
    case FormCtx(formComponentId: FormComponentId) => this :: Nil
    case Sum(field1: Expr) =>
      field1 match {
        case FormCtx(formComponentId) =>
          formModel.allFormComponents.collect {
            case fc if fc.baseComponentId == formComponentId.baseComponentId => FormCtx(fc.id)
          }
        case _ => field1.leafs(formModel)
      }
    case Count(formComponentId: FormComponentId)    => FormCtx(formComponentId.withFirstIndex) :: Nil
    case AuthCtx(value: AuthInfo)                   => this :: Nil
    case UserCtx(value: UserField)                  => this :: Nil
    case Constant(value: String)                    => this :: Nil
    case PeriodValue(value: String)                 => this :: Nil
    case HmrcRosmRegistrationCheck(value: RosmProp) => this :: Nil
    case Value                                      => this :: Nil
    case LangCtx                                    => this :: Nil
    case FormTemplateCtx(value: FormTemplateProp)   => this :: Nil
    case ParamCtx(_)                                => this :: Nil
    case LinkCtx(_)                                 => this :: Nil
    case DateCtx(dateExpr)                          => dateExpr.leafExprs
    case Period(dateCtx1, dateCtx2)                 => dateCtx1.leafs(formModel) ::: dateCtx2.leafs(formModel)
    case PeriodExt(periodFun, _)                    => periodFun.leafs(formModel)
    case AddressLens(formComponentId, _)            => this :: Nil
    case DataRetrieveCtx(_, _)                      => this :: Nil
  }

  def sums: List[Sum] = this match {
    case Add(field1: Expr, field2: Expr)            => field1.sums ++ field2.sums
    case Multiply(field1: Expr, field2: Expr)       => field1.sums ++ field2.sums
    case Subtraction(field1: Expr, field2: Expr)    => field1.sums ++ field2.sums
    case IfElse(cond, field1: Expr, field2: Expr)   => cond.allExpressions.flatMap(_.sums) ++ field1.sums ++ field2.sums
    case Else(field1: Expr, field2: Expr)           => field1.sums ++ field2.sums
    case FormCtx(formComponentId: FormComponentId)  => Nil
    case sum @ Sum(field1: Expr)                    => sum :: Nil
    case Count(field1: FormComponentId)             => Nil
    case AuthCtx(value: AuthInfo)                   => Nil
    case UserCtx(value: UserField)                  => Nil
    case Constant(value: String)                    => Nil
    case PeriodValue(value: String)                 => Nil
    case HmrcRosmRegistrationCheck(value: RosmProp) => Nil
    case Value                                      => Nil
    case FormTemplateCtx(value: FormTemplateProp)   => Nil
    case ParamCtx(_)                                => Nil
    case LinkCtx(_)                                 => Nil
    case LangCtx                                    => Nil
    case DateCtx(_)                                 => Nil
    case Period(_, _)                               => Nil
    case PeriodExt(_, _)                            => Nil
    case AddressLens(_, _)                          => Nil
    case DataRetrieveCtx(id, attribute)             => Nil
  }
}

final case class Add(field1: Expr, field2: Expr) extends Expr
final case class Multiply(field1: Expr, field2: Expr) extends Expr
final case class Subtraction(field1: Expr, field2: Expr) extends Expr
final case class IfElse(cond: BooleanExpr, field1: Expr, field2: Expr) extends Expr
final case class Else(field1: Expr, field2: Expr) extends Expr
final case class FormCtx(formComponentId: FormComponentId) extends Expr
final case class Sum(field1: Expr) extends Expr
final case class Count(formComponentId: FormComponentId) extends Expr
final case class ParamCtx(queryParam: QueryParam) extends Expr
final case class AuthCtx(value: AuthInfo) extends Expr
final case class UserCtx(value: UserField) extends Expr
final case class Constant(value: String) extends Expr
final case class PeriodValue(value: String) extends Expr
final case class LinkCtx(link: InternalLink) extends Expr
final case class HmrcRosmRegistrationCheck(value: RosmProp) extends Expr
final case object Value extends Expr
final case class FormTemplateCtx(value: FormTemplateProp) extends Expr
final case class DateCtx(value: DateExpr) extends Expr
final case class AddressLens(formComponentId: FormComponentId, detail: AddressDetail) extends Expr
final case class Period(dateCtx1: Expr, dateCtx2: Expr) extends Expr
final case object LangCtx extends Expr
final case class DataRetrieveCtx(id: DataRetrieveId, attribute: DataRetrieveAttribute) extends Expr

sealed trait PeriodFn
object PeriodFn {
  case object Sum extends PeriodFn
  case object TotalMonths extends PeriodFn
  case object Years extends PeriodFn
  case object Months extends PeriodFn
  case object Days extends PeriodFn
  implicit val format: OFormat[PeriodFn] = derived.oformat()
}
final case class PeriodExt(period: Expr, func: PeriodFn) extends Expr

sealed trait UserFieldFunc
object UserFieldFunc {
  case object Count extends UserFieldFunc
  case class Index(i: Int) extends UserFieldFunc

  implicit val format: OFormat[UserFieldFunc] = derived.oformat()
}

sealed trait AddressDetail {
  def toAddressAtom: Atom = this match {
    case AddressDetail.Line1    => Address.street1
    case AddressDetail.Line2    => Address.street2
    case AddressDetail.Line3    => Address.street3
    case AddressDetail.Line4    => Address.street4
    case AddressDetail.Postcode => Address.postcode
    case AddressDetail.Country  => Address.country
  }

  def toOverseasAddressAtom: Atom = this match {
    case AddressDetail.Line1    => OverseasAddress.line1
    case AddressDetail.Line2    => OverseasAddress.line2
    case AddressDetail.Line3    => OverseasAddress.line3
    case AddressDetail.Line4    => OverseasAddress.city
    case AddressDetail.Postcode => OverseasAddress.postcode
    case AddressDetail.Country  => OverseasAddress.country
  }
}

object AddressDetail {
  case object Line1 extends AddressDetail
  case object Line2 extends AddressDetail
  case object Line3 extends AddressDetail
  case object Line4 extends AddressDetail
  case object Postcode extends AddressDetail
  case object Country extends AddressDetail

  implicit val format: OFormat[AddressDetail] = derived.oformat()
}

object FormCtx {
  implicit val format: OFormat[FormCtx] = derived.oformat()
}

object Expr {
  val additionIdentity: Expr = Constant("0")
  implicit val format: OFormat[Expr] = derived.oformat()
  implicit val equal: Eq[Expr] = Eq.fromUniversalEquals
}

sealed trait RosmProp extends Product with Serializable
case object RosmSafeId extends RosmProp
case object RosmOrganisationName extends RosmProp
case object RosmOrganisationType extends RosmProp
case object RosmIsAGroup extends RosmProp

object RosmProp {
  implicit val format: OFormat[RosmProp] = derived.oformat()
}

sealed trait UserField {
  def fold[B](
    f: UserField.AffinityGroup.type => B
  )(g: UserField.Enrolment => B)(h: UserField.EnrolledIdentifier.type => B): B =
    this match {
      case UserField.AffinityGroup      => f(UserField.AffinityGroup)
      case e: UserField.Enrolment       => g(e)
      case UserField.EnrolledIdentifier => h(UserField.EnrolledIdentifier)
    }
}

object UserField {
  final case object AffinityGroup extends UserField
  final case class Enrolment(serviceName: ServiceName, identifierName: IdentifierName, func: Option[UserFieldFunc])
      extends UserField
  final case object EnrolledIdentifier extends UserField

  implicit val format: OFormat[UserField] = derived.oformat()
}

final case class ServiceName(value: String) extends AnyVal
object ServiceName {
  implicit val format: OFormat[ServiceName] = derived.oformat()
}

final case class IdentifierName(value: String) extends AnyVal
object IdentifierName {
  implicit val format: OFormat[IdentifierName] = derived.oformat()
}

sealed trait AuthInfo
final case object GG extends AuthInfo
final case object PayeNino extends AuthInfo
final case object EmailId extends AuthInfo
final case object SaUtr extends AuthInfo
final case object CtUtr extends AuthInfo
final case object EtmpRegistrationNumber extends AuthInfo

object AuthInfo {
  implicit val format: OFormat[AuthInfo] = derived.oformat()
}

sealed trait FormTemplateProp extends Product with Serializable
object FormTemplateProp {
  case object Id extends FormTemplateProp
  case object SubmissionReference extends FormTemplateProp

  implicit val format: OFormat[FormTemplateProp] = derived.oformat()
}
