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

package uk.gov.hmrc.gform.sharedmodel.formtemplate.generators

import org.scalacheck.Gen
import uk.gov.hmrc.gform.sharedmodel.ExampleData._
import uk.gov.hmrc.gform.sharedmodel.formtemplate.destinations.Destinations

trait DestinationsGen {

  def destinationListGen: Gen[Destinations.DestinationList] =
    PrimitiveGen
      .oneOrMoreGen(DestinationGen.destinationGen)
      .map(Destinations.DestinationList(_, ackSection, Some(decSection)))

  def destinationsGen: Gen[Destinations] = Gen.oneOf(destinationListGen, DestinationPrintGen.destinationPrintGen)
}

object DestinationsGen extends DestinationsGen
