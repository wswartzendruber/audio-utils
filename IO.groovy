public class IO {
	
	/*
	 * Displays a prompt to STDOUT and then listens for input on STDIN until EOL.
	 *
	 * @param prompt the prompt to display before waiting for input
	 *
	 * @return the line read from STDIN
	 */
	public static String readln(String prompt) {
		System.console().readLine(prompt);
	}
	
	/**
	 * Creates a temporary directory for use with the closure and removes it when complete.
	 */
	public static def withTempDir(Closure closure) {
		
		def rmrf = { parent ->
			
			parent.listFiles().each { child ->
				if (child.isDirectory())
					rmrf(child)
				else
					child.delete()
			}
			
			parent.delete()
		}
		
		def temp = new File("temp-${System.nanoTime()}")
		
		temp.mkdir()
		closure(temp)
		rmrf(temp)
	}
}

