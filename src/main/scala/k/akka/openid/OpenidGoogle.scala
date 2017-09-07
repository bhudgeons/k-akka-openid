package k.akka.openid

import akka.actor.{ActorSystem, TypedActor}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import com.nimbusds.jwt.SignedJWT
import spray.json._
import akka.http.scaladsl.server._
import akka.http.scaladsl.model.headers.{HttpCookie, Location}
import akka.http.scaladsl.server.Directives._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.Future
import OpenidProvider._

/**
 * Configure the google version of openid.
 * See [[https://developers.google.com/identity/protocols/OpenIDConnect Google Openid Connect]].
 *
 * @param client   Your client code given by Google
 * @param secret   Your client secret given by Google
 * @param redirect The redirection url Google should send the user after connection
 * @param external The external url used to redirect the user so he can connect itself
 * @param path     The path used in your url to access google version of openid
 */
case class OpenidGoogleSettings(
  client: String, secret: String, redirect: String,
  external: String = "https://accounts.google.com/o/oauth2/v2/auth", path: String = "google"
) extends OpenidProviderSettings

/**
 * Openid with Google as provider
 *
 * @see OpenIdProviderBuilder
 */
object OpenidGoogle extends OpenidProviderBuilder[OpenidGoogleSettings] with DefaultJsonProtocol {
  override def apply(settings: OpenidGoogleSettings)(implicit actorSystem: ActorSystem, materializer: ActorMaterializer, sessionsDuration:FiniteDuration): OpenidProvider =
    new OpenidGoogle(settings)

  /**
   * Converts result obtained from Google request fetching identity information.
   */
  implicit object GoogleProviderResultJson extends RootJsonReader[ProviderIdentification] {
    override def read(value: JsValue): ProviderIdentification = value match {
      case obj: JsObject =>
        obj.fields("id_token") match {
          case JsString(idToken) =>
            val claimsSet = SignedJWT.parse(idToken).getJWTClaimsSet
            ProviderIdentification(claimsSet.getIssuer, claimsSet.getSubject)
        }
    }
  }

}

/**
 * Openid with Google as provider
 *
 * @param settings The settings for google provider
 */
class OpenidGoogle(settings: OpenidGoogleSettings)(implicit actorSystem: ActorSystem, materializer: ActorMaterializer) extends OpenidProvider {
  override def providerpath: String = settings.path

  def buildRedirectURI(token: String): String = {
    val prefix = settings.external
    val parameters = Map(
      "client_id" -> settings.client,
      "response_type" -> "code",
      "scope" -> "openid",
      "redirect_uri" -> settings.redirect,
      "state" -> token
    ) map { case (key, value) => key + "=" + value } mkString "&"

    s"$prefix?$parameters"
  }

  def requestData(code: String): Future[ProviderIdentification] = {
    import OpenidGoogle._

    val parametersEntity = Map(
      "code" -> code,
      "client_id" -> settings.client,
      "client_secret" -> settings.secret,
      "redirect_uri" -> settings.redirect,
      "grant_type" -> "authorization_code"
    ) map { case (key, value) => key + "=" + value } mkString "&"

    val providerResult = for {
      response <- Http().singleRequest(HttpRequest(
        method = HttpMethods.POST,
        uri = s"https://www.googleapis.com/oauth2/v4/token",
        entity = HttpEntity(ContentType(MediaTypes.`application/x-www-form-urlencoded`, HttpCharsets.`UTF-8`), parametersEntity)
      ))
      strResult <- Unmarshal(response.entity).to[String]
    } yield strResult
    providerResult.map(_.parseJson.convertTo[ProviderIdentification])
  }

  override def extractTokenFromParameters(parameters: Map[String, String]): Option[String] = parameters.get("state")

  override def extractCodeFromParameters(parameters: Map[String, String]): Option[String] = parameters.get("code")
	def tokens(implicit actorSystem: ActorSystem, materializer: ActorMaterializer, sessionsDuration: FiniteDuration) = {
    TypedActor(actorSystem).typedActorOf(AutoRemovableMapActor.props[String, String](sessionsDuration))
	}
  /**
   * Builds the redirection response for given provider.
   */
  def redirection(implicit actorSystem: ActorSystem, materializer: ActorMaterializer, sessionsDuration: FiniteDuration): Route = {
    val openidTokenCookie = generateToken()
    setCookie(HttpCookie("openidToken", value = openidTokenCookie)) { ctx =>
      val token = generateToken()
      val hash = crypt(openidTokenCookie, token)
      tokens.add(openidTokenCookie, hash)
      val uri = buildRedirectURI(token)
      ctx.complete(HttpResponse(StatusCodes.TemporaryRedirect, headers = List(Location(uri))))
    }
  }
  /**
   * Calls the provider and sends the result to [[resultProcessor]]
   *
   * @param provider The provider to consider
   * @param code     The access code
   * @param ctx      The current request context
   * @return The request result
   */
  def callProvider(code: String, ctx: RequestContext): Future[RouteResult] = {
    val result = requestData(code)

    result flatMap { providerResult =>
      resultProcessor(OpenidResultSuccess(ctx, providerResult.provider, providerResult.pid))
    } recoverWith { case t =>
      resultProcessor(OpenidResultErrorThrown(ctx, t))
    }
  }

  /**
   * Builds the process for given provider and sends the result to [[resultProcessor]].
   */
  def response(implicit actorSystem: ActorSystem, materializer: ActorMaterializer, sessionsDuration: FiniteDuration): Route = {
    cookie("openidToken") { openidToken =>
      deleteCookie("openidToken") {
        parameterMap { parameters => ctx =>
          extractTokenFromParameters(parameters) map { tokenParameter =>
            tokens.get(openidToken.value) map { token =>
              if (crypt(openidToken.value, tokenParameter) == token) {
                extractCodeFromParameters(parameters) map { code =>
                  callProvider(code, ctx)
                } getOrElse {
                  resultProcessor(OpenidResultUndefinedCode(ctx))
                }
              } else {
                resultProcessor(OpenidResultInvalidState(ctx))
              }
            } getOrElse {
              resultProcessor(OpenidResultInvalidToken(ctx))
            }
          } getOrElse {
            resultProcessor(OpenidResultUndefinedToken(ctx))
          }
        }
      }
    }
  }
  /**
   * Builds one provider for redirection route and result route.
   *
   * @param provider The provider to consider
   * @return The built route
   */
  def buildOneProvider(routerSettings:OpenidRouterSettings)(implicit actorSystem: ActorSystem, materializer: ActorMaterializer, sessionsDuration: FiniteDuration): Route = {
    buildForProcess(providerpath, routerSettings.beforeProviderOnRedirection, routerSettings.afterProviderOnRedirection) {
      redirection
    } ~ buildForProcess(providerpath, routerSettings.beforeProviderOnResponse, routerSettings.afterProviderOnResponse) {
      response
    }
  }

  /**
   * Builds a route for given process in terms of optional parts before and after the route.
   * {{{
   *   // Examples:
   *   buildForProcess("a", Some("before"), Some("after")(process) => final url = /before/a/after
   *   buildForProcess("a", None,           Some("after")(process) => final url = /a/after
   * }}}
   *
   * @param providerPath The path of the provider
   * @param beforeOpt    The optional part before the provider path
   * @param afterOpt     The optional part after the provider path
   * @param process      The process to consider
   * @return The built route
   */
  private def buildForProcess(providerPath: String, beforeOpt: Option[String], afterOpt: Option[String])(process: Route) = {
    (beforeOpt, afterOpt) match {
      case (Some(before), Some(after)) =>
        pathPrefix(before) {
          pathPrefix(providerPath) {
            path(after) {
              process
            }
          }
        }
      case (Some(before), None) =>
        pathPrefix(before) {
          path(providerPath) {
            process
          }
        }
      case (None, Some(after)) =>
        pathPrefix(providerPath) {
          path(after) {
            process
          }
        }
      case (None, None) =>
        path(providerPath) {
          process
        }
    }
  }
  def resultProcessor:ResultProcessor = {
    // Each case represents a possible result the openid router provides
    // You will need to add your own logic for each result.
    // TODO: add support for path here?
    case OpenidResultSuccess(ctx, provider, pid, _, _) => {
      ctx.complete(s"(provider, pid) = ($provider, $pid)")
    }
    case OpenidResultUndefinedCode(ctx) =>
      ctx.complete("undefined code")
    case OpenidResultUndefinedToken(ctx) =>
      ctx.complete("undefined token")
    case OpenidResultInvalidToken(ctx) =>
      ctx.complete("invalid token")
    case OpenidResultInvalidState(ctx) =>
      ctx.complete("invalid state")
    case OpenidResultErrorThrown(ctx, error) =>
      ctx.complete("error: " + error.getMessage)
  }
}
