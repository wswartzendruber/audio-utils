import groovy.xml.MarkupBuilder

public class MKA {
	
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

