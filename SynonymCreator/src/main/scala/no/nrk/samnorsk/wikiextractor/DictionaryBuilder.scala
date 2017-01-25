package no.nrk.samnorsk.wikiextractor

import java.io.File

import com.typesafe.scalalogging.slf4j.StrictLogging
import scopt.RenderingMode.TwoColumns

import scala.io.Source

case class Config(from: String = "nno", to: String = "nob", limit: Option[Int] = None, topN: Int = 1,
                  input: Option[File] = None, output: Option[File] = None,
                  sourceTF: Int = 5, sourceIDF: Double = .5,
                  transTF: Int = 5, transIDF: Double = .5)

object DictionaryBuilder extends StrictLogging {
  def textToPairs(text: String, translator: ApertiumRunner): Traversable[(String, String)] = {
    for (sent <- SentenceSegmenter.segment(text);
         translation = translator.translate(sent);
         pair <- SimpleTextAligner.tokenDiscrepancy(sent, translation))
      yield pair
  }

  def wikiToCounts(it: WikiIterator, translator: ApertiumRunner,
                   counter: TranslationCounter[String, String]): TranslationCounter[String, String] = {
    for (article <- it) {
      counter.update(textToPairs(article, translator))
    }

    counter
  }

  def main(args: Array[String]): Unit = {
    val parser = new scopt.OptionParser[Config]("DictionaryBuilder") {
      head("DictionaryBuilder", "0.1.0")

      opt[String]('d', "direction")
        .action((x, c) => x.split("-") match { case Array(from, to) => c.copy(from = from, to = to) })
        .text("Translation direction (ex. nno-nob).")
      opt[Int]('l', "limit")
        .action((x, c) => c.copy(limit = Some(x)))
        .text("Maximum number of articles to process.")
      opt[String]('i', "input-file")
        .action((x, c) => c.copy(input = Some(new File(x))))
        .text("Input wikipedia dump")
        .required()
      opt[String]('o', "output-file")
        .action((x, c) => c.copy(output = Some(new File(x))))
        .text("Output dictionary file")
        .required()
      opt[Int]('S', "source-tf-filter")
        .action((x, c) => c.copy(sourceTF = x))
        .text("Minimum term frequency for source words")
      opt[Double]('s', "source-df-filter")
        .action((x, c) => c.copy(sourceIDF = x))
        .text("Maximum doc frequency for source words")
      opt[Int]('T', "trans-tf-filter")
        .action((x, c) => c.copy(transTF = x))
        .text("Minimum term frequency for translated words")
      opt[Double]('t', "trans-df-filter")
        .action((x, c) => c.copy(transIDF = x))
        .text("Maximum doc frequency for translated words")
      opt[Int]('n', "top-n")
        .action((x, c) => c.copy(topN = x))
        .text("Number of translations to keep")
    }

    parser.parse(args, Config()) match {
      case Some(config) =>
        config.limit match {
          case Some(l) => logger.info(s"Reading $l articles from ${config.input.get.getAbsolutePath}")
          case _ => logger.info(s"Reading all articles from ${config.input.get.getAbsolutePath}")
        }

        val translator = new LocalApertiumRunner(fromLanguage = config.from, toLanguage = config.to)
        val it = new WikiIterator(Source.fromFile(config.input.get), limit = config.limit)
        val counter = wikiToCounts(it, translator,
          new TranslationCounter[String, String](
            sourceTfFilter = config.sourceTF, sourceDfFilter = config.sourceIDF,
            transTfFilter = config.transTF, transDfFilter = config.transIDF, topN = Some(config.topN)))
        counter.write(config.output.get)
      case None =>
        parser.renderUsage(TwoColumns)
    }
  }
}
