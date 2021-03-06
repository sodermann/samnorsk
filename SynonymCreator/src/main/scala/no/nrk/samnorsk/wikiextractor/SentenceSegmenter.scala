package no.nrk.samnorsk.wikiextractor

import epic.preprocess.MLSentenceSegmenter

object SentenceSegmenter {
  private val segmenter = MLSentenceSegmenter.bundled().get

  def segment(text: String) : Seq[String] = {
    segmenter(text).map(_.trim)
  }
}
