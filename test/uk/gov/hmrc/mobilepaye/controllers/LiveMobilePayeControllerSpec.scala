/*
 * Copyright 2019 HM Revenue & Customs
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

package uk.gov.hmrc.mobilepaye.controllers

import play.api.libs.json.Json
import play.api.mvc.AnyContentAsEmpty
import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.auth.core.ConfidenceLevel.{L100, L200}
import uk.gov.hmrc.auth.core.syntax.retrieved._
import uk.gov.hmrc.auth.core.{AuthConnector, MissingBearerToken}
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.http._
import uk.gov.hmrc.mobilepaye.config.MobilePayeControllerConfig
import uk.gov.hmrc.mobilepaye.domain.tai.Person
import uk.gov.hmrc.mobilepaye.domain.{MobilePayeResponse, Shuttering}
import uk.gov.hmrc.mobilepaye.services.MobilePayeService
import uk.gov.hmrc.mobilepaye.utils.BaseSpec
import uk.gov.hmrc.play.audit.http.connector.AuditConnector

import scala.concurrent.{ExecutionContext, Future}

case class TestMobilePayeControllerConfig(shuttering: Shuttering) extends MobilePayeControllerConfig


class LiveMobilePayeControllerSpec extends BaseSpec {

  val fakeRequest: FakeRequest[AnyContentAsEmpty.type] = FakeRequest().withHeaders(acceptHeader)
  val journeyId = "12345"

  val mockMobilePayeService: MobilePayeService = mock[MobilePayeService]

  implicit val mockAuditConnector:   AuditConnector   = mock[AuditConnector]
  implicit val mockAuthConnector:    AuthConnector    = mock[AuthConnector]

  private val shuttered  = Shuttering(shuttered = true, "Shuttered", "PAYE is currently not available")
  private val notShuttered = Shuttering(shuttered = false, "", "")

  def controller(shuttering: Shuttering) =
    new LiveMobilePayeController(
      mockAuthConnector,
      200,
      mockMobilePayeService,
      stubControllerComponents(),
      mockAuditConnector,
      "mobile-paye",
      TestMobilePayeControllerConfig(shuttering)
    )

  def mockGetMobilePayeResponse(f: Future[MobilePayeResponse]) =
    (mockMobilePayeService.getMobilePayeResponse(_: Nino, _: Int)(_: HeaderCarrier, _: ExecutionContext)).expects(*, *, *, *).returning(f)

  def mockGetPerson(f: Future[Person]) =
    (mockMobilePayeService.getPerson(_: Nino)(_: HeaderCarrier, _: ExecutionContext)).expects(*, *, *).returning(f)

  "getPayeSummary" should {
    "return 200 and full paye summary data for valid authorised nino" in {
      mockGetPerson(Future.successful(person))
      mockGetMobilePayeResponse(Future.successful(fullMobilePayeResponse))
      mockAuthorisationGrantAccess(Some(nino.toString) and L200)
      mockAudit(nino, fullMobilePayeAudit, journeyId)

      val result = controller(notShuttered).getPayeSummary(nino, journeyId, currentTaxYear)(fakeRequest)

      status(result)        shouldBe 200
      contentAsJson(result) shouldBe Json.toJson(fullMobilePayeResponse)
    }

    "return 200 and paye summary data with no employment data for valid authorised nino" in {
      mockGetPerson(Future.successful(person))
      mockGetMobilePayeResponse(Future.successful(fullMobilePayeResponse.copy(employments = None)))
      mockAuthorisationGrantAccess(Some(nino.toString) and L200)
      mockAudit(nino, fullMobilePayeAudit.copy(employments = None), journeyId)

      val result = controller(notShuttered).getPayeSummary(nino, journeyId, currentTaxYear)(fakeRequest)

      status(result)        shouldBe 200
      contentAsJson(result) shouldBe Json.toJson(fullMobilePayeResponse.copy(employments = None))
    }

    "return 200 and paye summary data with no pensions data for valid authorised nino" in {
      mockGetPerson(Future.successful(person))
      mockGetMobilePayeResponse(Future.successful(fullMobilePayeResponse.copy(pensions = None)))
      mockAuthorisationGrantAccess(Some(nino.toString) and L200)
      mockAudit(nino, fullMobilePayeAudit.copy(pensions = None), journeyId)

      val result = controller(notShuttered).getPayeSummary(nino, journeyId, currentTaxYear)(fakeRequest)

      status(result)        shouldBe 200
      contentAsJson(result) shouldBe Json.toJson(fullMobilePayeResponse.copy(pensions = None))
    }

    "return 200 and paye summary data with no other income data for valid authorised nino" in {
      mockGetPerson(Future.successful(person))
      mockGetMobilePayeResponse(Future.successful(fullMobilePayeResponse.copy(otherIncomes = None)))
      mockAuthorisationGrantAccess(Some(nino.toString) and L200)
      mockAudit(nino, fullMobilePayeAudit.copy(otherIncomes = None), journeyId)

      val result = controller(notShuttered).getPayeSummary(nino, journeyId, currentTaxYear)(fakeRequest)

      status(result)        shouldBe 200
      contentAsJson(result) shouldBe Json.toJson(fullMobilePayeResponse.copy(otherIncomes = None))
    }

    "return 423 for a valid nino and authorised user but corrupt/mcierror user" in {
      mockAuthorisationGrantAccess(Some(nino.toString) and L200)
      mockGetPerson(Future.failed(Upstream4xxResponse("locked", 423,423)))

      val result = controller(notShuttered).getPayeSummary(nino, journeyId, currentTaxYear)(fakeRequest)

      status(result) shouldBe 423
    }

    "return 410 for a valid nino and authorised user but deceased user" in {
      mockAuthorisationGrantAccess(Some(nino.toString) and L200)
      mockGetPerson(Future.successful(person.copy(isDeceased = true)))

      val result = controller(notShuttered).getPayeSummary(nino, journeyId, currentTaxYear)(fakeRequest)

      status(result) shouldBe 410
    }

    "return 500 when MobilePayeService throws an InternalServerException" in {
      mockAuthorisationGrantAccess(Some(nino.toString) and L200)
      mockGetPerson(Future.successful(person))
      mockGetMobilePayeResponse(Future.failed(new InternalServerException("Internal Server Error")))

      val result = controller(notShuttered).getPayeSummary(nino, journeyId, currentTaxYear)(fakeRequest)

      status(result) shouldBe 500
    }

    "return 401 for valid nino and user but low CL" in {
      mockAuthorisationGrantAccess(Some(nino.toString) and L100)

      val result = controller(notShuttered).getPayeSummary(nino, journeyId, currentTaxYear)(fakeRequest)

      status(result) shouldBe 401
    }

    "return 406 for missing accept header" in {

      val result = controller(notShuttered).getPayeSummary(nino, journeyId, currentTaxYear)(FakeRequest("GET", "/"))

      status(result) shouldBe 406
    }

    "return 403 for valid nino for authorised user but for a different nino" in {
      mockAuthorisationGrantAccess(Some(nino.toString) and L200)

      val result = controller(notShuttered).getPayeSummary(Nino("CS100700A"), journeyId, currentTaxYear)(fakeRequest)

      status(result) shouldBe 403
    }

    "return 404 when handling NotFoundException" in {
      mockAuthorisationGrantAccess(Some(nino.toString) and L200)
      mockGetPerson(Future.failed(new NotFoundException("Not Found Exception")))

      val result = controller(notShuttered).getPayeSummary(nino, journeyId, currentTaxYear)(fakeRequest)

      status(result) shouldBe 404
    }

    "return 400 when handling BadRequestException" in {
      mockAuthorisationGrantAccess(Some(nino.toString) and L200)
      mockGetPerson(Future.failed(new BadRequestException("Bad Request Exception")))

      val result = controller(notShuttered).getPayeSummary(nino, journeyId, currentTaxYear)(fakeRequest)

      status(result) shouldBe 400
    }

    "return 401 when handling 401 Upstream4xxResponse" in {
      mockAuthorisationGrantAccess(Some(nino.toString) and L200)
      mockGetPerson(Future.failed(Upstream4xxResponse("Upstream Exception", 401, 401)))

      val result = controller(notShuttered).getPayeSummary(nino, journeyId, currentTaxYear)(fakeRequest)

      status(result) shouldBe 401
    }

    "return 401 when handling AuthorisationException" in {
      mockAuthorisationGrantAccess(Some(nino.toString) and L200)
      mockGetPerson(Future.failed(new MissingBearerToken))

      val result = controller(notShuttered).getPayeSummary(nino, journeyId, currentTaxYear)(fakeRequest)

      status(result) shouldBe 401
    }

    "return 521 when shuttered" in {
      mockAuthorisationGrantAccess(Some(nino.toString) and L200)
      val result = controller(shuttered).getPayeSummary(nino, journeyId, currentTaxYear)(fakeRequest)

      status(result) shouldBe 521
      val jsonBody = contentAsJson(result)
      (jsonBody \ "shuttered").as[Boolean] shouldBe true
      (jsonBody \ "title").as[String]      shouldBe "Shuttered"
      (jsonBody \ "message").as[String]    shouldBe "PAYE is currently not available"
    }
  }
}
