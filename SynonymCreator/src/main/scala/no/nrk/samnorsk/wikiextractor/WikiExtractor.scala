package no.nrk.samnorsk.wikiextractor
import java.io.{File, FileInputStream}
import java.lang.Thread.UncaughtExceptionHandler
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, StandardOpenOption}
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import epic.preprocess.MLSentenceSegmenter
import resource._

import scala.io.{Codec, Source}
import scala.sys.process._

trait Language {
  val Aperitum: String
  val Wiki: String
  val Name: String
}

object Nynorsk extends Language {
  override val Aperitum: String = "nno"
  override val Wiki: String = "nn"
  override val Name: String = "nn"
}

object Bokmaal extends Language {
  override val Aperitum: String = "nob"
  override val Wiki: String = "no"
  override val Name: String = "nb"
}

object SentenceSegmenter {
  val segmenter = MLSentenceSegmenter.bundled().get

  def segment(text: String) : Seq[String] = {
    segmenter(text).map(_.trim)
  }
}

class Counter {
  private val counter = new AtomicInteger()
  def next() = counter.incrementAndGet()
}

object ApertiumHelper {

  def translate(input: String, fromLanguage: Language, toLanguage: Language, counter: Counter): String = {
    val tempInputFile = File.createTempFile("apertium-input", fromLanguage.Name)
    try {
      Files.write(tempInputFile.toPath, input.getBytes(StandardCharsets.UTF_8))
      val chunkNumber = counter.next()
      println(s"Started translating chunk $chunkNumber from ${fromLanguage.Name} to ${toLanguage.Name}")
      val trans = s"apertium ${fromLanguage.Aperitum}-${toLanguage.Aperitum} ${tempInputFile.getAbsolutePath}".!!.trim
      println(s"Done translating chunk $chunkNumber")
      trans
    } finally {
      Files.delete(tempInputFile.toPath)
    }
  }
}

object WikiExtractor {

  @JsonIgnoreProperties(ignoreUnknown = true)
  case class Article(text: String)
  case class ArticleAndTranslation(original: String, translation: String)

  val JsonMapper = new ObjectMapper().registerModule(DefaultScalaModule)
  val DateRegex = """20[\d]{6}""".r

  def downloadLatest(language: Language): File = {
    val dates: Seq[String] = Source.fromURL("https://dumps.wikimedia.org/other/cirrussearch/current/")
      .getLines()
      .filter(_.contains(s"${language.Wiki}wiki-"))
      .toSeq
      .flatMap(line => DateRegex.findFirstIn(line))
      .distinct

    require(dates.length == 1, "Unable to find latest date for wiki dump")
    val downloadedFile = new File(s"/tmp/${language.Name}.gz")
    val url = s"https://dumps.wikimedia.org/other/cirrussearch/current/${language.Wiki}wiki-${dates.head}-cirrussearch-content.json.gz"
    println(s"Downloading $url")
    new URL(url) #> downloadedFile !!

    println(s"Done downloading $url")
    downloadedFile
  }

  def resolveDump(fileMaybe: Option[String], language: Language) = {
    fileMaybe match {
      case Some(dumpFile) =>
        val file = new File(dumpFile)
        require(file.exists(), s"$dumpFile does not exist.")
        file
      case None => downloadLatest(language)
    }
  }

  def writeOutput(articles: Seq[ArticleAndTranslation], outputFile: File) = {
    for (article <- articles) {
      Files.write(outputFile.toPath, (JsonMapper.writeValueAsString(article) + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND)
    }
  }

  def translateDump(dump: File, fromLanguage: Language, toLanguage: Language, translationFile: File) = {

    val counter = new Counter

    def translateChunk(listOfArticles: Seq[String]) = {
      listOfArticles
        // Group articles to something reasonable to avoid initializing Apertium for each article.
        .grouped(100)
        // Apertium will remove \n, we use ☃☃¤ as a magic article separator.
        .map(listOfArticles => listOfArticles.mkString("☃☃¤"))
        .toSeq.par
        .foreach(originalText => {
          val translation = ApertiumHelper.translate(originalText, fromLanguage, toLanguage, counter)
          val articles = originalText.split("☃☃¤").zip(translation.split("☃☃¤"))
            .map(x => ArticleAndTranslation(x._1, x._2))
          writeOutput(articles, translationFile)
        })
    }

    managed(Source.fromInputStream(new GZIPInputStream(new FileInputStream(dump)))(Codec.UTF8))
      .acquireAndGet(source => {
        source.getLines()
          .map(article => JsonMapper.readValue(article, classOf[Article]))
          .filter(x => x.text != null)
          .filter(_.text.length > 100)
          .map(article => article.text)
          .grouped(10000)
          .foreach(chunk => translateChunk(chunk))
      })
  }

  def wipeAndCreateNewFile(file: File) = {
    if (file.exists()) {
      Files.delete(file.toPath)
    }
    file.createNewFile()
  }

  def main(args: Array[String]): Unit = {
    Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionHandler {
      override def uncaughtException(t: Thread, e: Throwable): Unit = {
        println(s"Uncaught exception, exiting. ${e.getMessage}")
        sys.exit(1)
      }
    })

    type OptionMap = Map[String, String]
    def parseOptions(map : OptionMap, list: List[String]) : OptionMap = {
      list match {
        case Nil => map
        case "--nndump" :: value :: tail =>
          parseOptions(map ++ Map(Nynorsk.Name -> value), tail)
        case "--nbdump" :: value :: tail =>
          parseOptions(map ++ Map(Bokmaal.Name -> value), tail)
        case "--nntrans" :: value :: tail =>
          parseOptions(map ++ Map("nynorsktobokmaal" -> value), tail)
        case "--nbtrans" :: value :: tail =>
          parseOptions(map ++ Map("bokmaaltonynorsk" -> value), tail)
        case option :: tail => throw new IllegalArgumentException("Unknown option " + option)
      }
    }

    val options: Map[String, String] = parseOptions(Map(), args.toList)
    val outputNnToNb = new File(options.getOrElse("nynorsktobokmaal", throw new IllegalArgumentException("Nynorsk to Bokmaal output file is not defined")))
    val outputNbToNn = new File(options.getOrElse("bokmaaltonynorsk", throw new IllegalArgumentException("Bokmaal to Nynorsk output file is not defined")))

    wipeAndCreateNewFile(outputNnToNb)
    wipeAndCreateNewFile(outputNbToNn)

    val nynorskDump = resolveDump(options.get(Nynorsk.Name), Nynorsk)
    val bokmaalDump = resolveDump(options.get(Bokmaal.Name), Bokmaal)

    println("Dumps resolved, starting translation")
    translateDump(bokmaalDump, Bokmaal, Nynorsk, outputNbToNn)
    translateDump(nynorskDump, Nynorsk, Bokmaal, outputNnToNb)
  }
}
