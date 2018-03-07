#!/usr/bin/env groovy

/*
 * This script will read a 2011 Matroska audio track from my personal library and update it to a 2018 one.
 */

import groovy.util.XmlSlurper
import groovy.xml.MarkupBuilder

/**
 * Creates a temporary directory for use with the closure and removes it when complete.
 */
def withTempDir(Closure closure) {
	
	def rmrf = { parent ->
		
		parent.listFiles().each { child ->
			if (child.isDirectory())
				rmrf(child)
			else
				child.delete()
		}
		
		parent.delete()
	}
	
	def temp = File.createTempFile("mka-update-", System.nanoTime().toString())
	
	temp.delete()
	temp.mkdir()
	
	closure(temp)
	
	rmrf(temp)
}

/**
 * Reads the 2011 Matroska file and outputs the cover attachment.
 *
 * @param mka    the 2011 Matroska file
 * @param output the output cover attachment
 */
void dumpCover(File mka, File output) {
	
	def mkvextractProcess = [ "mkvextract", "attachments", mka, "1:${output}" ].execute()
	
	if (mkvextractProcess.waitFor())
		throw new Exception("mkvextract process exited unsuccessfully.")
}

/**
 * Reads the 2011 Matroska file and outputs the first audio track.
 *
 * @param mka    the 2011 Matroska file
 * @param output the output FLAC file
 */
void dumpFlac(File mka, File output) {
	
	def mkvextractProcess = [ "mkvextract", "tracks", mka, "0:${output}" ].execute()
	
	if (mkvextractProcess.waitFor())
		throw new Exception("mkvextract process exited unsuccessfully.")
}

/**
 * Parses 2011 Matroska tags and returns an array of sample counts, one for each track.
 *
 * @param tags the {@code XmlSlurper} tags
 *
 * @return an array of sample counts, one for each track
 */
List<Long> parseTrackLengths(def tags) {
	
	def sampleLengths = []
	
	tags.Tag.findAll({ Tag -> Tag?.Targets?.TargetTypeValue == "30" }) \
			.Simple?.findAll({ Simple -> Simple?.Name == "SAMPLES" }) \
			.each { Simple -> sampleLengths += Long.parseLong(Simple.String.toString()) }
	
	return sampleLengths
}

/**
 * Parses 2011 Matroska tags and returns an array of names, one for each track.
 *
 * @param tags the {@code XmlSlurper} tags
 *
 * @return an array of names, one for each track
 */
List<String> parseTrackNames(def tags) {
	
	def trackNames = []
	
	tags.Tag.findAll({ Tag -> Tag?.Targets?.TargetTypeValue == "30" }) \
			.Simple?.findAll({ Simple -> Simple?.Name == "TITLE" }) \
			.each { Simple -> trackNames += Simple.String.toString() }
	
	return trackNames
}

/**
 * Parses 2011 Matroska tags and returns a single, album-wide value.
 *
 * @param tags  the {@code XmlSlurper} tags
 * @param value the name of the album-wide value to parse
 *
 * @return a single, album-wide value
 */
String parseValue(def tags, String name) {
	
	def returnValue = null
	
	tags.Tag.findAll({ Tag -> Tag?.Targets?.TargetTypeValue == "50" }) \
			.Simple?.findAll({ Simple -> Simple?.Name == name }) \
			.each { Simple -> returnValue = Simple?.String.toString() }
	
	if (returnValue == null)
		throw new Exception("Requested album-wide value '${name}' could not be parsed from tags.")
	
	return returnValue
}

/**
 * Reads a 2011 Matroska file and extracts the tags out of it.
 *
 * @param mka the 2011 Matroska file
 *
 * @return the tag structure via {@code XmlSlurper}
 */
def readTags(File mka) {
	
	def mkvextractProcess = [ "mkvextract", "tags", mka ].execute()
	def tags              = new XmlSlurper().parse(mkvextractProcess.inputStream)
	
	if (mkvextractProcess.waitFor())
		throw new Exception("mkvextract process exited unsuccessfully.")
	
	return tags
}

/**
 * Reads a 2011 Matroska track and gets its sample rate.
 *
 * @param mka the 2011 Matroska file
 *
 * @return the sample rate
 */
int sampleRate(File mka) {
	
	def pattern        = /^\|   \+ Sampling frequency: (\d+)$/
	def mkvinfoProcess = [ "mkvinfo", mka ].execute()
	def bufferedReader = new BufferedReader(new InputStreamReader(mkvinfoProcess.getInputStream()))
	def sampleRate
	
	bufferedReader.eachLine { line ->
		if (line ==~ pattern)
			if (!sampleRate)
				sampleRate = (line =~ pattern)[0][1].toInteger()
			else
				throw new Exception("Detected multiple possible sample rates.")
	}
	
	if (mkvinfoProcess.waitFor())
		throw new Exception("mkvinfo process exited unsuccessfully.")
	
	if (!sampleRate)
		throw new Exception("No sample rate detected.")
	
	return sampleRate
}

/**
 * Returns a formatted timestamp reflecting the sample count.
 *
 * @param samples    the sample count
 * @param sampleRate the sample rate
 */
String timeStamp(long samples, int sampleRate) {
	
	def samplesPerHour   = sampleRate * 60.0 * 60.0
	def samplesPerMinute = sampleRate * 60.0
	def otherFactor      = sampleRate / 1000000000.0
	
	def hours       = Math.floor(samples / samplesPerHour).intValue()
	def minutes     = Math.floor((samples - hours * samplesPerHour) / samplesPerMinute).intValue()
	def seconds     = Math.floor((samples - hours * samplesPerHour - minutes * samplesPerMinute) / sampleRate).intValue()
	def nanoseconds = Math.floor((samples - hours * samplesPerHour - minutes * samplesPerMinute - seconds * sampleRate) \
			/ otherFactor).intValue()
	
	return "${String.format("%02d", hours)}:${String.format("%02d", minutes)}:" \
			+ "${String.format("%02d", seconds)}.${String.format("%9s", nanoseconds).substring(0, 9).replace(" ", "0")}"
}

/**
 * Writes the chapter listing using the track lengths and track names.
 *
 * @param trackLength a list of integers corresponding to the sample count of each track, in order
 * @param trackNames  a list of corresponding to the name of each track, in order
 * @param output      the file to write the chapter listing to in UTF-8
 */
void writeChapterListing(List<Integer> trackLengths, List<String> trackNames, List<Long> trackUids, int sampleRate \
		, File output) {
	
	if (trackLengths.size() != trackNames.size())
		throw new Exception("INTERNAL ERROR: trackLengths and trackNames must have the same element count.")
	
	def outputStream = new FileOutputStream(output)
	def writer       = new PrintWriter(new OutputStreamWriter(outputStream, "UTF-8"))
	def xml          = new MarkupBuilder(writer)
	def total        = 0
	
	xml.mkp.xmlDeclaration(version: "1.0", encoding: "utf-8")
	
	xml.Chapters() {
		EditionEntry() {
			trackLengths.eachWithIndex { length, index ->
				ChapterAtom() {
					ChapterUID(trackUids[index])
					ChapterTimeStart(timeStamp(total, sampleRate))
					ChapterDisplay() {
						ChapterString(trackNames[index])
						ChapterLanguage("und")
					}
				}
				total += length
			}
		}
	}
	
	writer.flush()
	outputStream.close()
}

/**
 * Writes the Matroska XML tags to an output file.
 *
 * @param artist       the artist of the album
 * @param album        the title of the album
 * @param genre        the genre of the album
 * @param trackLengths the sample length of each track, in order
 * @param trackNames   the name of each track, in order
 * @param output       the file to write the chapter listing to in UTF-8
 */
void writeTags(String artist, String album, String year, String genre, List<Integer> trackLengths, List<String> trackNames
		, List<Long> trackUids, File output) {
	
	def outputStream = new FileOutputStream(output)
	def writer       = new PrintWriter(new OutputStreamWriter(outputStream, "UTF-8"))
	def xml          = new MarkupBuilder(writer)
	
	xml.mkp.xmlDeclaration(version: "1.0", encoding: "utf-8")
	
	xml.Tags() {
		Tag() {
			Targets() {
				TargetTypeValue(50)
			}
			Simple() {
				Name("TITLE")
				String(album)
			}
			Simple() {
				Name("ARTIST")
				String(artist)
			}
			Simple() {
				Name("TOTAL_PARTS")
				String(trackLengths.size())
			}
			Simple() {
				Name("DATE_RELEASED")
				String(year)
			}
			Simple() {
				Name("GENRE")
				String(genre)
			}
		}
		trackLengths.eachWithIndex { length, index ->
			Tag() {
				Targets() {
					TargetTypeValue(30)
					ChapterUID(trackUids[index])
				}
				Simple() {
					Name("TITLE")
					String(trackNames[index])
				}
				Simple() {
					Name("PART_NUMBER")
					String(index + 1)
				}
			}
		}
	}
	
	writer.flush()
	outputStream.close()
}

/**
 * Muxes the final Matroska output.
 *
 * @param flac     the temporary FLAC file
 * @param cover    the temporary cover file
 * @param tags     the temporary tags file
 * @param chapters the temporary chapters file
 * @param title    the title for the Matroska output file
 * @param output   the output Matroska file
 */
void writeMatroskaMux(File flac, File cover, File tags, File chapters, String title, File output) {
	
	def mkvmergeProcess = [ "mkvmerge", "--disable-track-statistics-tags", "--output", output, "--title", title, "--chapters" \
			, chapters, "--global-tags", tags, "--attachment-name", "Cover", "--attachment-mime-type", "image/jpeg" \
			, "--attach-file", cover, flac ].execute()
	
	if (mkvmergeProcess.waitFor())
		throw new Exception("mkvmerge process exited unsuccessfully.")
}

/*
 * Displays a prompt to STDOUT and then listens for input on STDIN until EOL.
 *
 * @param prompt the prompt to display before waiting for input
 *
 * @return the line read from STDIN
 */
String readln(String prompt) {
	System.console().readLine(prompt);
}

/*
 * BEGIN
 */

if (args.length != 2) {
	System.err.println("ERROR: Script must be passed a 2011 Matroska and output file name.")
	System.err.println("USAGE: mka-update.groovy [old-matroska] [output]")
	return 1
}

withTempDir { tempDir ->
	
	println("Updating '${args[0]}' to '${args[1]}'")
	
	def random       = new Random()
	def oldMka       = new File(args[0])
	def outputFile   = new File(args[1])
	def flacFile     = new File(tempDir, "audio.flac")
	def chaptersFile = new File(tempDir, "chapters.xml")
	def tagsFile     = new File(tempDir, "tags.xml")
	def coverFile    = new File(tempDir, "cover.jpg")
	def sampleRate   = sampleRate(oldMka)
	def tags         = readTags(oldMka)
	def trackLengths = parseTrackLengths(tags)
	def trackNames   = parseTrackNames(tags)
	def trackUids    = []
	def artist       = parseValue(tags, "ARTIST")
	def album        = parseValue(tags, "TITLE")
	def year         = parseValue(tags, "DATE_RECORDED")
	def genre        = parseValue(tags, "GENRE")
	
	dumpCover(oldMka, coverFile)
	dumpFlac(oldMka, flacFile)
	
	trackLengths.size().times { trackUids.push(Math.abs(random.nextLong())) }
	
	writeChapterListing(trackLengths, trackNames, trackUids, sampleRate, chaptersFile)
	writeTags(artist, album, year, genre, trackLengths, trackNames, trackUids, tagsFile)
	writeMatroskaMux(flacFile, coverFile, tagsFile, chaptersFile, "${artist}: ${album}", outputFile)
	
	println("DONE")
}
