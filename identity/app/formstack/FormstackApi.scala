package formstack

import com.google.inject.{Singleton, Inject}
import idapiclient.IdDispatchAsyncHttpClient
import utils.SafeLogging
import net.liftweb.json._
import client.connection.HttpResponse
import scala.concurrent.Future
import client.{Error, Response}
import com.gu.identity.model.LiftJsonConfig
import client.parser.JodaJsonSerializer
import common.ExecutionContexts
import conf.Configuration

@Singleton
class FormstackApi @Inject()(httpClient: IdDispatchAsyncHttpClient) extends ExecutionContexts with SafeLogging {

  implicit val formats = LiftJsonConfig.formats + new JodaJsonSerializer

  def formstackUrl(formId: String) = {
    val formstackUrl = Configuration.formstack.url
    s"$formstackUrl/form/$formId.json"
  }

  def checkForm(formstackForm: FormstackForm): Future[Response[FormstackForm]] = {

    httpClient.GET(formstackUrl(formstackForm.formId), Iterable("oauth_token" -> Configuration.formstack.oAuthToken)) map {
      case Left(errors) => Left(errors)
      case Right(HttpResponse(body, statusCode, _)) => {
        statusCode match {
          case 200 => {
            logger.trace("Formstack API returned 200 for reference lookup")
            val json = parse(body)
            (for {
              formId <- (json \ "id").extractOpt[String]
              inactive <- (json \ "inactive").extractOpt[Boolean]
            } yield {
              if (formstackForm.formId == formId && !inactive) {
                logger.trace(s"Formstack reference $formId was good")
                Right(formstackForm)
              } else {
                logger.warn(s"Form, '$formId' is valid but not enabled (request formId vs response formId: ${formstackForm.formId} - $formId, inactive: $inactive)")
                Left(List(Error("Invalid form", "This is not a valid form", 404)))
              }
            }).getOrElse {
              logger.warn(s"200 received from Formstack for '${formstackForm.formId}', but response was invalid $body")
              Left(List(Error("Invalid Formstack API response", "")))
            }
          }
          case 405 => {
            logger.warn("405 returned while checking formstack reference")
            Left(List(Error("Invalid form reference", "Invalid form reference", 405)))
          }
          case 404 => {
            logger.warn(s"Attempted to load bad formstack reference (404) $body")
            Left(List(Error("Form not found", "Form not found", 404)))
          }
          case _ => {
            logger.warn(s"Unexpected error getting info for formstack reference. Status code $statusCode, body $body")
            Left(List(Error("Form error", "Unexpected error retrieving form info", statusCode)))
          }
        }
      }
    }
  }
}