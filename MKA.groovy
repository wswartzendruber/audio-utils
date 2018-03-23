import groovy.util.XmlSlurper
import groovy.xml.MarkupBuilder

public class MKA {
	
	/**
	 * Reads a Matroska track and gets its channel count.
	 *
	 * @param mka the Matroska file
	 *
	 * @return the channel count
	 */
	public static int channelCount(File mka) {
		
		def pattern        = /^\|   \+ Channels: (\d+)$/
		def mkvinfoProcess = [ "mkvinfo", mka ].execute()
		def bufferedReader = new BufferedReader(new InputStreamReader(mkvinfoProcess.getInputStream()))
		def channelCount
		
		bufferedReader.eachLine { line ->
			if (line ==~ pattern)
				if (!channelCount)
					channelCount = (line =~ pattern)[0][1].toInteger()
				else
					throw new Exception("Detected multiple possible channel counts.")
		}
		
		if (mkvinfoProcess.waitFor())
			throw new Exception("mkvinfo process exited unsuccessfully.")
		
		if (!channelCount)
			throw new Exception("No channel count detected.")
		
		return channelCount
	}
	
	/**
	 * Reads a Matroska file and outputs the cover attachment.
	 *
	 * @param mka    the Matroska file
	 * @param output the output cover attachment
	 */
	public static void dumpCover(File mka, File output) {
		
		def mkvextractProcess = [ "mkvextract", "attachments", mka, "1:${output}" ].execute()
		
		if (mkvextractProcess.waitFor())
			throw new Exception("mkvextract process exited unsuccessfully.")
	}
	
	/**
	 * Reads a Matroska file and outputs the first audio track.
	 *
	 * @param mka    the 2011 Matroska file
	 * @param output the output FLAC file
	 */
	public static void dumpFlac(File mka, File output) {
		
		def mkvextractProcess = [ "mkvextract", "tracks", mka, "0:${output}" ].execute()
		
		if (mkvextractProcess.waitFor())
			throw new Exception("mkvextract process exited unsuccessfully.")
	}
	
	/**
	 * Parses a Matroska chapters and returns an array of sample counts, one for each track, of where each track starts.
	 *
	 * @param chapters   the {@code XmlSlurper} chapters
	 * @param sampleRate the sample rate of the audio tracks
	 *
	 * @return an array of sample lengths, one for each track, of where each track starts
	 */
	public static List<Long> parseChapterStartPoints(def chapters, int sampleRate) {
		
		def startPoints = []
		
		chapters.EditionEntry.ChapterAtom.each \
				{ startPoints += parseTimeStamp(it.ChapterTimeStart.toString(), sampleRate) }
		
		return startPoints
	}
	
	/**
	 * Parses Matroska chapters and returns an array of names, one for each track.
	 *
	 * @param chapters the {@code XmlSlurper} chapters
	 *
	 * @return an array of names, one for each track
	 */
	public static List<String> parseChapterNames(def chapters) {
		
		def trackNames = []
		
		chapters.EditionEntry.ChapterAtom.each { trackNames += it.ChapterDisplay.ChapterString.toString() }
		
		return trackNames
	}
	
	/**
	 * Parses a time stamp into its sample length.
	 *
	 * @param timeStamp  the time stamp string in the format HH:MM:SS.NNNNNNNNN
	 * @param sampleRate the sample rate factor to use
	 *
	 * @return the sample count for the specified time stamp at the specified sample rate
	 */
	private static long parseTimeStamp(String timeStamp, int sampleRate) {
		
		def pattern = /^(\d\d):(\d\d):(\d\d).(\d\d\d\d\d\d\d\d\d)$/
		
		if (timeStamp ==~ pattern) {
			
			def segments = (timeStamp =~ pattern)[0]
			
			return segments[1].toLong() * 3600 * sampleRate \
					+ segments[2].toLong() * 60 * sampleRate \
					+ segments[3].toLong() * sampleRate \
					+ Math.round((segments[4].toDouble() / 1000000000.0) * sampleRate)
			
		} else {
			
			throw new Exception("Time code '${timeStamp}' cannot be parsed.")
		}
	}
	
	/**
	 * Parses Matroska tags and returns a single, album-wide value.
	 *
	 * @param tags  the {@code XmlSlurper} tags
	 * @param value the name of the album-wide value to parse
	 *
	 * @return a single, album-wide value
	 */
	public static String parseValue(def tags, String name) {
		
		def returnValue = null
		
		tags.Tag.findAll({ Tag -> Tag?.Targets?.TargetTypeValue == "50" }) \
				.Simple?.findAll({ Simple -> Simple?.Name == name }) \
				.each { Simple -> returnValue = Simple?.String.toString() }
		
		if (returnValue == null)
			throw new Exception("Requested album-wide value '${name}' could not be parsed from tags.")
		
		return returnValue
	}
	
	/**
	 * Reads a Matroska file and extracts the chapters out of it.
	 *
	 * @param mka the Matroska file
	 *
	 * @return the chapter structure via {@code XmlSlurper}
	 */
	public static def readChapters(File mka) {
		
		def mkvextractProcess = [ "mkvextract", "chapters", mka ].execute()
		def chapters          = new XmlSlurper().parse(mkvextractProcess.inputStream)
		
		if (mkvextractProcess.waitFor())
			throw new Exception("mkvextract process exited unsuccessfully.")
		
		return chapters
	}
	
	/**
	 * Reads a Matroska file and extracts the tags out of it.
	 *
	 * @param mka the Matroska file
	 *
	 * @return the tag structure via {@code XmlSlurper}
	 */
	public static def readTags(File mka) {
		
		def mkvextractProcess = [ "mkvextract", "tags", mka ].execute()
		def tags              = new XmlSlurper().parse(mkvextractProcess.inputStream)
		
		if (mkvextractProcess.waitFor())
			throw new Exception("mkvextract process exited unsuccessfully.")
		
		return tags
	}
	
	/**
	 * Reads a Matroska track and gets its sample rate.
	 *
	 * @param mka the Matroska file
	 *
	 * @return the sample rate
	 */
	public static int sampleRate(File mka) {
		
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
	 * Writes the chapter listing using the track lengths and track names.
	 *
	 * @param trackLength a list of integers corresponding to the sample count of each track, in order
	 * @param trackNames  a list of corresponding to the name of each track, in order
	 * @param output      the file to write the chapter listing to in UTF-8
	 */
	public static void writeChapterListing(List<Integer> trackLengths, List<String> trackNames, List<Long> trackUids \
			, File output) {
		
		if (trackLengths.size() != trackNames.size() || trackNames.size() != trackUids.size())
			throw new Exception("trackLengths, trackNames and trackUids must all have the same element count.")
		
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
						ChapterTimeStart(timeStamp(total, 44100))
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
	public static void writeTags(String artist, String album, String year, String genre, List<Integer> trackLengths \
			, List<String> trackNames, List<Long> trackUids, File output) {
		
		if (trackLengths.size() != trackNames.size() || trackNames.size() != trackUids.size())
			throw new Exception("trackLengths, trackNames and trackUids must all have the same element count.")
		
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
	public static void writeMatroskaMux(File flac, File cover, File tags, File chapters, String title, File output) {
		
		def mkvmergeProcess = [ "mkvmerge", "--disable-track-statistics-tags", "--output", output, "--title", title \
				, "--chapters", chapters, "--global-tags", tags, "--attachment-name", "Cover", "--attachment-mime-type" \
				, "image/jpeg", "--attach-file", cover, flac ].execute()
		
		if (mkvmergeProcess.waitFor())
			throw new Exception("mkvmerge process exited unsuccessfully.")
	}
	
	/**
	 * Returns a formatted time stamp reflecting the sample count.
	 *
	 * @param samples    the sample count
	 * @param sampleRate the sample rate
	 *
	 * @return formatted time stamp
	 */
	private static String timeStamp(long samples, int sampleRate) {
		
		def samplesPerHour   = sampleRate * 3600.0
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
}

