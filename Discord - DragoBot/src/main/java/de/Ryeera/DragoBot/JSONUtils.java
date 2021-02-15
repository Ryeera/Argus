package de.Ryeera.DragoBot;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import org.json.JSONObject;

public class JSONUtils {

	public static JSONObject readJSON(File source) throws IOException {
		String         json = "";
		BufferedReader in   = new BufferedReader(new FileReader(source));
		String         line;
		while ((line = in.readLine()) != null)
			if (!line.equals(""))
				json += line;
		in.close();
		return new JSONObject(json);
	}
	
	public static void writeJSON(JSONObject json, File file) throws FileNotFoundException {
		PrintWriter out = new PrintWriter(file);
		out.print(json.toString(2));
		out.flush();
		out.close();
	}
}
