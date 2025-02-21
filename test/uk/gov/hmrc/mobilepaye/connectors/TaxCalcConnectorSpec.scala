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

package uk.gov.hmrc.mobilepaye.connectors

import uk.gov.hmrc.http._
import uk.gov.hmrc.mobilepaye.domain.taxcalc.TaxYearReconciliation
import uk.gov.hmrc.mobilepaye.utils.BaseSpec

import scala.concurrent.{ExecutionContext, Future}

class TaxCalcConnectorSpec extends BaseSpec {
  val mockCoreGet: CoreGet          = mock[CoreGet]
  val serviceUrl:  String           = "tax-calc-url"
  val connector:   TaxCalcConnector = new TaxCalcConnector(mockCoreGet, serviceUrl, withTaxCalc = true)

  def mockTaxCalcGet[T](f: Future[T]) =
    (mockCoreGet
      .GET(_: String)(_: HttpReads[T], _: HeaderCarrier, _: ExecutionContext))
      .expects(s"$serviceUrl/taxcalc/${nino.value}/reconciliations", *, *, *)
      .returning(f)

  "getTaxReconciliations" should {
    "not fail for some Throwable" in {
      mockTaxCalcGet(Future.failed(new Throwable("")))

      val result: Option[List[TaxYearReconciliation]] = await(connector.getTaxReconciliations(nino))
      result shouldBe None
    }
  }
}
