package controllers

import model.{ Trail, Cached, Content }
import play.api.mvc.{ RequestHeader, Action, Controller }
import common.{ Site, JsonComponent, Logging, JsonNotFound }
import org.joda.time.format.DateTimeFormat
import conf.ContentApi
import feed.Competitions._
import org.scala_tools.time.Imports._
import pa.FootballMatch
import implicits.{ Requests, Football }
import play.api.libs.concurrent.Execution.Implicits._
import concurrent.Future

case class Report(trail: Trail, name: String)

case class MatchNav(theMatch: FootballMatch, matchReport: Option[Trail],
    minByMin: Option[Trail], stats: Trail, currentPage: Option[Trail]) {

  // do not count stats as a report (stats will always be there)
  lazy val hasReports = hasReport || hasMinByMin
  lazy val hasMinByMin = minByMin.isDefined
  lazy val hasReport = matchReport.isDefined
}

object MoreOnMatchController extends Controller with Football with Requests with Logging {

  private val dateFormat = DateTimeFormat.forPattern("yyyyMMdd")

  // note team1 & team2 are the home and away team, but we do NOT know their order
  def matchNav(year: String, month: String, day: String, team1: String, team2: String) = Action { implicit request =>

    val contentDate = dateFormat.parseDateTime(year + month + day).toDateMidnight
    val interval = new Interval(contentDate - 2.days, contentDate + 3.days)

    matchFor(interval, team1, team2).map { theMatch =>
      val promiseOfRelated = loadMoreOn(request, theMatch)
      Async {
        // for our purposes here, we are only interested in content with exactly 2 team tags
        promiseOfRelated.map(_.filter(_.tags.filter(_.isFootballTeam).length == 2)).map { related =>
          related match {
            case Nil => Cached(300)(JsonNotFound())
            case _ => Cached(300)(JsonComponent(
              "nav" -> views.html.fragments.matchNav(populateNavModel(theMatch, withExactlyTwoTeams(related))))
            )
          }
        }
      }
    }.getOrElse(Cached(300)(JsonNotFound()))
  }

  def moreOn(matchId: String) = Action { implicit request =>
    findMatch(matchId).map { theMatch =>
      val promiseOfRelated = loadMoreOn(request, theMatch)
      Async {
        promiseOfRelated.map { related =>
          related match {
            case Nil => Cached(300)(JsonNotFound())
            case _ => Cached(300)(JsonComponent(
              ("nav" -> views.html.fragments.matchNav(populateNavModel(theMatch, withExactlyTwoTeams(related)))),
              ("related" -> views.html.fragments.relatedTrails(related, "More on this match", 5)))
            )
          }
        }
      }
    }.getOrElse(Cached(300)(JsonNotFound()))
  }

  def loadMoreOn(request: RequestHeader, theMatch: FootballMatch): Future[Seq[Content]] = {
    val matchDate = theMatch.date.toDateMidnight
    ContentApi.search(Site(request).edition)
      .section("football")
      .tag("tone/matchreports|football/series/squad-sheets|football/series/saturday-clockwatch")
      .fromDate(matchDate.minusDays(2))
      .toDate(matchDate.plusDays(2))
      .reference(s"pa-football-team/${theMatch.homeTeam.id},pa-football-team/${theMatch.awayTeam.id}")
      .response.map{ response =>
        response.results.map(new Content(_))
    }
  }

  //for our purposes we expect exactly 2 football teams
  private def withExactlyTwoTeams(content: Seq[Content]) = content.filter(_.tags.filter(_.isFootballTeam).size == 2)

  private def populateNavModel(theMatch: FootballMatch, related: Seq[Content])(implicit request: RequestHeader) = {
    val matchDate = theMatch.date.toDateMidnight
    val matchReport = related.find { c => c.webPublicationDate >= matchDate && c.matchReport && !c.minByMin }
    val minByMin = related.find { c => c.webPublicationDate.toDateMidnight == matchDate && c.matchReport && c.minByMin }
    val stats: Trail = theMatch

    val currentPage = request.getParameter("currentPage").flatMap { pageId =>
      (stats :: List(matchReport, minByMin).flatten).find(_.url.endsWith(pageId))
    }

    MatchNav(theMatch, matchReport, minByMin, stats, currentPage)
  }
}