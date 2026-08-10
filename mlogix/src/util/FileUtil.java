package mlogix.util;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

public class FileUtil {
	public static String readAll(String filePath) {
		String outString = "";
		try {
			BufferedReader in = new BufferedReader(new FileReader(filePath));
			StringBuffer stringBuffer;
			while (in.ready()) {
				stringBuffer = new StringBuffer(in.readLine());
				outString += stringBuffer + "\n";
			}
			in.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return outString;
	}
	
	public static BufferedReader getReader(String filePath) throws IOException {
		try {
			return new BufferedReader(new FileReader(filePath));
		} catch (IOException e) {
			e.printStackTrace();
			throw e;
		}
	}

}