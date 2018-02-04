#!/usr/bin/env groovy

/*
 * This script will rip a CD into a single Matroska audio file with a single FLAC stream.  It depends on mkvtoolnix, flac and
 * cdparanoia being installed and in the current PATH.  The MKA file will be tagged and contain precise chapter points for each
 * track on the disc.
 */

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
	
	def temp = File.createTempFile("cd2mka-", System.nanoTime().toString())
	
	temp.delete()
	temp.mkdir()
	
	closure(temp)
	
	rmrf(temp)
}

/**
 * Reads the CDDA device and outputs the entire bitstream in FLAC format.
 *
 * @param device the device descriptor (e.g. "/dev/sr0")
 * @param output the file to write the FLAC bitstream to
 */
void writeDiscAsFlac(String device, File output) {
	
	/*
	 * Both the cdparanoia and flac processes will run concurrently.
	 */
	
	def cdparanoiaProcess     = [ "cdparanoia", "--force-cdrom-device", device, "--output-wav", "1-", "-" ].execute()
	def flacProcess           = [ "flac", "--verify", "--best", "-" ].execute()
	def cdparanoiaInputStream = cdparanoiaProcess.getInputStream()
	def flacInputStream       = flacProcess.getInputStream()
	def flacOutputStream      = flacProcess.getOutputStream()
	def cdparanoiaBuffer      = new byte[1024 * 1024]
	def cdparanoiaLength      = 0
	def flacBuffer            = new byte[1024 * 1024]
	def flacLength            = 0
	def outputStream          = new FileOutputStream(output)
	
	/*
	 * In order to keep the pipeline from seizing, we need independent threads that independently monitor output from one
	 * process to feed to the input of the next process.
	 */
	
	def cdparanoiaThread = Thread.start {
		while ((cdparanoiaLength = cdparanoiaInputStream.read(cdparanoiaBuffer)) != -1)
			flacOutputStream.write(cdparanoiaBuffer, 0, cdparanoiaLength)
	}
	
	def flacThread = Thread.start {
		while ((flacLength = flacInputStream.read(flacBuffer)) != -1)
			outputStream.write(flacBuffer, 0, flacLength)
	}
	
	cdparanoiaThread.join();
	flacThread.join();
	
	outputStream.close()
	
	/*
	 * The pipe threads being done doesn't necessarily mean the processes themselves have finished.
	 */
	
	if (cdparanoiaProcess.waitFor())
		throw new Exception("cdparanoia process exited unsuccessfully.")
	
	if (flacProcess.waitFor())
		throw new Exception("flac process exited unsuccessfully.")
}

/**
 * Reads the CDDA track index and returns an array of sample counts, one for each track.
 *
 * @param device the device descriptor
 *
 * @return an array of sample counts, one for each track
 */
List<Integer> readTrackLengths(String device) {
	
	def pattern           = /^ {1,2}\d{1,2}\. +(\d+) \[\d\d:\d\d\.\d\d\] +\d+ \[\d\d:\d\d\.\d\d\] +\w+ + \w+ +\d+$/
	def cdparanoiaProcess = [ "cdparanoia", "--force-cdrom-device", device, "--query" ].execute()
	def bufferedReader    = new BufferedReader(new InputStreamReader(cdparanoiaProcess.getErrorStream()))
	def sampleLengths     = []
	
	bufferedReader.eachLine { line ->
		if (line ==~ pattern)
			sampleLengths << (line =~ pattern)[0][1].toInteger() * 588
	}
	
	if (cdparanoiaProcess.waitFor())
		throw new Exception("cdparanoia process exited unsuccessfully.")
	
	return sampleLengths
}

/**
 * Writes the chapter listing using the track lengths and track names.
 *
 * @param trackLength a list of integers corresponding to the sample count of each track, in order
 * @param trackNames  a list of corresponding to the name of each track, in order
 * @param output      the file to write the chapter listing to in UTF-8
 */
void writeChapterListing(List<Integer> trackLengths, List<String> trackNames, File output) {
	
	if (trackLengths.size() != trackNames.size())
		throw new Exception("INTERNAL ERROR: trackLengths and trackNames must have the same element count.")
	
	def outputStream = new FileOutputStream(output)
	def writer       = new PrintWriter(new OutputStreamWriter(outputStream, "UTF-8"))
	def total        = 0 
	
	trackLengths.eachWithIndex { trackLength, trackIndex ->
		
		def hours        = Math.floor(total / 158760000).intValue()
		def minutes      = Math.floor((total - hours * 158760000) / 2646000).intValue()
		def seconds      = Math.floor((total - hours * 158760000 - minutes * 2646000) / 44100).intValue()
		def milliseconds = Math.floor((total - hours * 158760000 - minutes * 2646000 - seconds * 44100) / 44.1).intValue()
		def chapterBase  = "CHAPTER${String.format("%02d", trackIndex + 1)}"
		
		writer.println("${chapterBase}=${String.format("%02d", hours)}:${String.format("%02d", minutes)}:" +
				"${String.format("%02d", seconds)}.${String.format("%03d", milliseconds)}")
		writer.println("${chapterBase}NAME=${trackNames[trackIndex]}")
		
		total += trackLength
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
		, File output) {
	
	def outputStream = new FileOutputStream(output)
	def writer       = new PrintWriter(new OutputStreamWriter(outputStream, "UTF-8"))
	def xml          = new MarkupBuilder(writer)
	def random       = new Random()
	
	xml.Tags() {
		Tag() {
			Targets() {
				TargetTypeValue("50")
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
				Name("DATE_RECORDED")
				String(year)
			}
			Simple() {
				Name("GENRE")
				String(genre)
			}
		}
		trackLengths.eachWithIndex { trackLength, trackIndex ->
			Tag() {
				Targets() {
					TargetTypeValue("30")
				}
				Simple() {
					Name("TITLE")
					String(trackNames[trackIndex])
				}
				Simple() {
					Name("PART_NUMBER")
					String(String.format("%02d", trackIndex + 1))
				}
				Simple() {
					Name("SAMPLES")
					String(trackLength)
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

if (args.length != 3) {
	System.err.println("ERROR: Script must be passed a CD-ROM device, album art file (JPEG), and output file name.")
	System.err.println("USAGE: cd2mka.groovy [cdda-device] [cover-art] [output]")
	return 1
}

withTempDir { tempDir ->
	
	def device       = args[0]
	def coverFile    = new File(args[1])
	def outputFile   = new File(args[2])
	def flacFile     = new File(tempDir, "audio.flac")
	def chaptersFile = new File(tempDir, "chapters.txt")
	def tagsFile     = new File(tempDir, "tags.xml")
	def trackLengths = readTrackLengths(device)
	def trackNames   = []
	
	println("Beginning background rip...")
	println()
	
	def ripThread = Thread.start {
		writeDiscAsFlac(device, flacFile)
	}
	
	def artist = readln("Artist: ")
	def album  = readln("Album.: ")
	def year   = readln("Year..: ")
	def genre  = readln("Genre.: ")
	
	println()
	
	trackLengths.eachWithIndex { item, index ->
		trackNames << readln("Track ${String.format("%02d", index + 1)}: ")
	}
	
	writeChapterListing(trackLengths, trackNames, chaptersFile)
	writeTags(artist, album, year, genre, trackLengths, trackNames, tagsFile)
	
	println()
	print("Still ripping...")
	
	ripThread.join()
	
	println("DONE")
	print("Muxing to Matroska...")
	
	writeMatroskaMux(flacFile, coverFile, tagsFile, chaptersFile, "${artist}: ${album}", outputFile)
	
	println("DONE")
}
