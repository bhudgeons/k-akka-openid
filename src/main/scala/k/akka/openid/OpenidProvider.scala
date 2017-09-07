package k.akka.openid

import akka.actor.ActorSystem
import akka.http.scaladsl.server.{RequestContext, RouteResult}
import akka.stream.ActorMaterializer
import akka.http.scaladsl.model.headers.{HttpCookie, Location}
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import java.security.MessageDigest

import scala.concurrent.Future
import scala.util.Random
import akka.actor.{ActorSystem, TypedActor}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.ExecutionContext.Implicits.global

object OpenidProvider {

  type ResultProcessor = Function[OpenidResult, Future[RouteResult]]

  /**
   * Result of openid process
   */
  sealed trait OpenidResult {
    /**
     * Returns the request context, from akka http route structure
     */
    def requestContext: RequestContext

    /**
     * Indicates if the result is a success or a failure
     */
    def success: Boolean
  }
	trait LoginInfo

  /**
   * The result is a success.
   *
   * @param requestContext The request context
   * @param provider       The openid provider
   * @param pid            The openid user id for given provider
   */
  case class OpenidResultSuccess(requestContext: RequestContext, 
		provider: String, 
		pid: String, 
		loginInfo:Option[LoginInfo] = None, 
		path:Option[String] = None) extends OpenidResult {
    override def success: Boolean = true
  }

  /**
   * The openid provider did not returned a code.
   * This error should happen only when the request is forged.
   *
   * @param requestContext The request context
   */
  case class OpenidResultUndefinedCode(requestContext: RequestContext) extends OpenidResult {
    override def success: Boolean = false
  }

  /**
   * The request contains any token in its parameters.
   * This error should happen only when request is forged.
   *
   * @param requestContext The request context
   */
  case class OpenidResultUndefinedToken(requestContext: RequestContext) extends OpenidResult {
    override def success: Boolean = false
  }

  /**
   * The client does not have any token defined for it.
   * This error should happen when the user is too long to connect in the given provider or when the url was forged.
   *
   * @param requestContext The request context
   */
  case class OpenidResultInvalidToken(requestContext: RequestContext) extends OpenidResult {
    override def success: Boolean = false
  }

  /**
   * The token from request and the one defined for given client does not match.
   *
   * @param requestContext The request context
   */
  case class OpenidResultInvalidState(requestContext: RequestContext) extends OpenidResult {
    override def success: Boolean = false
  }

  /**
   * There is an error during processing
   *
   * @param requestContext The request context
   * @param error          The error thrown
   */
  case class OpenidResultErrorThrown(requestContext: RequestContext, error: Throwable) extends OpenidResult {
    override def success: Boolean = false
  }

}

/**
 * Defines the interface all openid providers should validate.
 * These methods are used in [[OpenidRouter]] to process requests.
 */
trait OpenidProvider {
	import OpenidProvider._
  /**
   * The prefix in routes.
   */
  def providerpath: String

  /**
   * Builds the uri used to redirect the client to the provider sso page.
   *
   * @param token The request token, used to avoid request forgery
   * @return The built uri
   */
  //def buildRedirectURI(token: String): String

  /**
   * Fetchs user information from provider with given authorization code.
   *
   * @param code The authorization code obtained from user acceptation on target provider
   * @return The identification of the user for this provider
   */
  //def requestData(code: String): Future[ProviderIdentification]

  /**
   * Extracts the token from parameters.
   * This token was sent to and returned from provider to avoid request forgery.
   *
   * @param parameters The map of parameters from the request
   * @return The extracted token if defined
   */
  def extractTokenFromParameters(parameters: Map[String, String]): Option[String]

  /**
   * Extracts the authorization code from parameters.
   * This code will be used to fetch user information from provider.
   *
   * @param parameters The map of parameters from the request
   * @return The extracted code if defined
   */
  def extractCodeFromParameters(parameters: Map[String, String]): Option[String]
    
  /**
   * Builds the redirection response for given provider.
   */
  //def redirection(implicit actorSystem: ActorSystem, materializer: ActorMaterializer, sessionsDuration: FiniteDuration): Route

  /**
   * Builds the process for given provider and sends the result to [[resultProcessor]].
   */
	//def response(implicit actorSystem: ActorSystem, materializer: ActorMaterializer, sessionsDuration: FiniteDuration): Route 
  /**
   * Generates a random token.
   */
  def generateToken(): String = Random.alphanumeric.take(50).mkString
  /**
   * Crypts a string with a salt.
   *
   * @param str  The string to crypt
   * @param salt The salt to use
   * @return The crypted string
   */
  def crypt(str: String, salt: String) = {
    // Code inspired of [[akka.util.Crypt]] which is now deprecated and could be removed in the future.
    val hex = "0123456789ABCDEF"
    val bytes = (str + salt).getBytes("ASCII")
    MessageDigest.getInstance("SHA1").update(bytes)
    val builder = new java.lang.StringBuilder(bytes.length * 2)
    bytes.foreach { byte ⇒ builder.append(hex.charAt((byte & 0xF0) >> 4)).append(hex.charAt(byte & 0xF)) }
    builder.toString
  }
  /**
   * List of tokens, which will be removed when outdated (after [[sessionsDuration]]).
   */
  //private lazy val tokens: AutoRemovableMapActor[String, String] = getTokens

	
  /**
   * Builds one provider for redirection route and result route.
   *
   * @param provider The provider to consider
   * @return The built route
   */
	def buildOneProvider(routerSettings:OpenidRouterSettings)(implicit actorSystem: ActorSystem, materializer: ActorMaterializer, sessionsDuration: FiniteDuration): Route 
}

/**
 * All objects extending from this trait will be considered as an openid provider builder.
 *
 * @tparam A The type used for settings for given openid provider
 */
trait OpenidProviderBuilder[A <: OpenidProviderSettings] {
  /**
   * Builds the openid provider, with given settings.
   * See openid provider settings type for more information
   *
   * @return The built openid provider
   */
  def apply(settings: A)(implicit actorSystem: ActorSystem, materializer: ActorMaterializer, sessionsDuration:FiniteDuration): OpenidProvider
}

trait OpenidProviderSettings {
  /**
   * The path to use for given provider
   */
  def path: String
}

/**
 * The identification information returned by the provider
 *
 * @param provider The provider url
 * @param pid      The user id for given provider
 */
case class ProviderIdentification(provider: String, pid: String)
