/*
 * Any copyright is dedicated to the Public Domain.
 * https://creativecommons.org/publicdomain/zero/1.0/
 */

/*
 * This script will rip a CD into a single Matroska audio file with a single FLAC stream.  It depends on mkvtoolnix, flac and
 * cdparanoia being installed and in the current PATH.  The MKA file will be tagged and contain precise chapter points for each
 * track on the disc.
 */

import IO
import MKA

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

/*
 * BEGIN
 */

if (args.length != 3) {
	System.err.println("ERROR: Script must be passed a CD-ROM device, album art file (JPEG), and output file name.")
	System.err.println("USAGE: cd2mka.groovy [cdda-device] [cover-art] [output]")
	return 1
}

IO.withTempDir { tempDir ->

	def random       = new Random()
	def device       = args[0]
	def coverFile    = new File(args[1])
	def outputFile   = new File(args[2])
	def flacFile     = new File(tempDir, "audio.flac")
	def chaptersFile = new File(tempDir, "chapters.xml")
	def tagsFile     = new File(tempDir, "tags.xml")
	def trackLengths = readTrackLengths(device)
	def trackNames   = []
	def trackUids    = []

	println("Beginning background rip...")
	println()

	def ripThread = Thread.start {
		writeDiscAsFlac(device, flacFile)
	}

	def artist = IO.readln("Artist: ")
	def album  = IO.readln("Album.: ")
	def year   = IO.readln("Year..: ")
	def genre  = IO.readln("Genre.: ")

	println()

	trackLengths.eachWithIndex { item, index ->
		trackNames << IO.readln("Track ${String.format("%02d", index + 1)}: ")
	}

	trackLengths.size().times { trackUids.push(Math.abs(random.nextLong())) }

	MKA.writeChapterListing(trackLengths, trackNames, trackUids, chaptersFile)
	MKA.writeTags(artist, album, year, genre, trackLengths, trackNames, trackUids, tagsFile)

	println()
	print("Still ripping...")

	ripThread.join()

	println("DONE")
	print("Muxing to Matroska...")

	MKA.writeMatroskaMux(flacFile, coverFile, tagsFile, chaptersFile, "${artist}: ${album}", outputFile)

	println("DONE")
}
