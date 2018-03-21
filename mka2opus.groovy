/*
 * This script will read a Matroska audio track from my personal library and generate a set of Opus files for it.
 */

import IO
import MKA

/**
 * Decodes a single FLAC to multiple WAV files according to the length of each one in samples.
 *
 * @param flac             the FLAC file to decode
 * @param target           the directory to extract the files to
 * @param trackStartPoints a list of integers representing the sample length of each track
 */
void decodeFlacToTracks(File flac, File target, List<Long> trackStartPoints) {
	
	trackStartPoints.eachWithIndex { startPoint, index ->
		
		def output   = new File(target, "${index}.wav")
		def flacArgs = [ "flac", "--decode", "--output-name=${output}", "--skip=${startPoint}", flac ]
		
		if (index < (trackStartPoints.size() - 1))
			flacArgs += "--until=${trackStartPoints[index + 1]}"
		
		if (flacArgs.execute().waitFor())
			throw new Exception("flac process exited unsuccessfully.")
	}
}

/**
 * Encodes an uncompressed WAV file to an Opus file with the specified metadata.
 *
 * @param wav     the WAV file to compress
 * @param opus    the output Opus file
 * @param bitrate the bitrate per (possibly) coupled channel
 * @param title   the title of the track
 * @param artist  the artisrt of the track
 * @param album   the album name of the track
 * @param date    the date of the track, can be YYYY-MM-DD, YYYY-MM, or simply YYYY
 * @param genre   the genre of the track
 * @param cover   the front cover file of the track
 */
void encodeOpus(File wav, File opus, int bitrate, String title, String artist, String album, String date, String genre \
		, File cover) {
	
	def opusencProcess = [ "opusenc", "--bitrate", bitrate, "--title", title, "--artist", artist, "--album", album \
			, "--date", date, "--genre", genre, "--picture", cover, "--discard-comments", wav, opus ].execute()
	
	if (opusencProcess.waitFor())
			throw new Exception("opusenc process exited unsuccessfully.")
}

/**
 * Strips any possible invalid characters from a file path and returns the sanitized output.
 *
 * @param input the unsanitized file path
 *
 * @return the sanitized output
 */
String sanitizeFilePath(String input) {
	
	return input.replaceAll("[^a-zA-Z0-9\\s\\.\\-]", "_")
}

/*
 * BEGIN
 */

if (args.length != 2) {
	System.err.println("ERROR: Script must be passed a Matroska audio file and output directory.")
	System.err.println("USAGE: mka2opus.groovy [mka] [music-location]")
	System.exit(1)
}

IO.withTempDir { tempDir ->
	
	println("Generating Opus set from '${args[0]}' to '${args[1]}'")
	
	def mka              = new File(args[0])
	def outputDir        = new File(args[1])
	def flacFile         = new File(tempDir, "audio.flac")
	def coverFile        = new File(tempDir, "cover.jpg")
	def sampleRate       = MKA.sampleRate(mka)
	def tags             = MKA.readTags(mka)
	def chapters         = MKA.readChapters(mka)
	def trackStartPoints = MKA.parseChapterStartPoints(chapters, sampleRate)
	def trackNames       = MKA.parseChapterNames(chapters)
	def artist           = MKA.parseValue(tags, "ARTIST")
	def album            = MKA.parseValue(tags, "TITLE")
	def year             = MKA.parseValue(tags, "DATE_RELEASED")
	def genre            = MKA.parseValue(tags, "GENRE")
	def albumDir         = new File(new File(outputDir, sanitizeFilePath(artist)), sanitizeFilePath(album))
	
	MKA.dumpCover(mka, coverFile)
	MKA.dumpFlac(mka, flacFile)
	decodeFlacToTracks(flacFile, tempDir, trackStartPoints)
	
	if (albumDir.exists()) {
		System.err.println("Directory '${albumDir}' already exists.")
		System.exit(2)
	} else {
		albumDir.mkdirs()
	}

	trackNames.eachWithIndex { name, index ->
		encodeOpus(new File(tempDir, "${index}.wav"), new File(albumDir \
				, "${String.format("%02d", index + 1)}. ${sanitizeFilePath(name)}.opus"), 128, name \
				, artist, album, year, genre, coverFile)
	}
	
	println("DONE")
}
