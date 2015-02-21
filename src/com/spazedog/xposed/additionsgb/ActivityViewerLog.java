package com.spazedog.xposed.additionsgb;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.v7.widget.Toolbar;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.LinearLayout;
import android.widget.TextView;

public class ActivityViewerLog extends Activity {
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		
		setContentView(R.layout.error_log_view);
		
		File[] files = new File[]{Common.LogFile.STORED, Common.LogFile.MAIN};
		StringBuilder builder = new StringBuilder();
		
		for (File file : files) {
			if (file.exists()) {
				BufferedReader reader = null;

				try {
					reader = new BufferedReader(new FileReader(file));
					String line;
					
					while ((line = reader.readLine()) != null) {
						builder.append(line);
						builder.append("\n");
					}
					
				}  catch (IOException e) {} finally {
					try {
						reader.close();
						
					} catch (Throwable e) {}
				}
			}
		}
		
		TextView view = (TextView) findViewById(R.id.content);
		view.setText( builder.toString() );
		
		if (Build.VERSION.SDK_INT >= 14) {
			Toolbar bar = (Toolbar) findViewById(R.id.toolbar);
			bar.setTitle(R.string.category_title_logviewer);
		}
	}
	
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.menu, menu);
		
		return true;
	}
	
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		File file = new File(getCacheDir(), "error.log");
		
		try {
			TextView view = (TextView) findViewById(R.id.content);
			BufferedWriter fileWriter = new BufferedWriter(new FileWriter(file, false));
			fileWriter.write( (String) view.getText() );
			fileWriter.close();
			
		} catch (IOException e) {}
		
		switch (item.getItemId()) {
			case R.id.menu_edit: 
				if (file.exists()) {
					try {
						Intent intent = new Intent(Intent.ACTION_VIEW);
						intent.setDataAndType(Uri.parse("file://" + file.getCanonicalPath()), "text/plain");
						
						startActivity(Intent.createChooser(intent, getResources().getString(R.string.menu_title_edit)));
						
					} catch (IOException e) {}
				}
				
				return true;
				
			case R.id.menu_send: 
				if (file.exists()) {
					try {
						Intent intent = new Intent(Intent.ACTION_SEND);
						intent.putExtra(Intent.EXTRA_EMAIL, new String[] {"gerhard.nospam@gmail.com"}); //TODO: temporary
						intent.putExtra(Intent.EXTRA_SUBJECT, "XposedAdditions: Error Log");
						intent.putExtra(Intent.EXTRA_TEXT, getDeviceInfo());
						intent.setType("text/plain");
						intent.putExtra(Intent.EXTRA_STREAM, Uri.parse("file://" + file.getCanonicalPath()));
						
						startActivity(Intent.createChooser(intent, getResources().getString(R.string.menu_title_send)));
					
					} catch (IOException e) {}
				}
				
				return true;
		}
		
		return super.onOptionsItemSelected(item);
	}
	
	protected String getDeviceInfo() {
		StringBuilder builder = new StringBuilder();
		
		Integer versionCode = 0;
		String versionName = "";
		
		try {
			PackageInfo info = getPackageManager().getPackageInfo(Common.PACKAGE_NAME, 0);
			
			versionCode = info.versionCode;
			versionName = info.versionName;
			
		} catch (NameNotFoundException e) {}
		
		builder.append("Module Version: (").append(versionCode).append(") ").append(versionName).append("\r\n");
		builder.append("-----------------\r\n");
		builder.append("Manufacturer: ").append(Build.MANUFACTURER).append("\r\n");
		builder.append("Brand: ").append(Build.BRAND).append("\r\n");
		builder.append("Device: ").append(Build.DEVICE).append("\r\n");
		builder.append("Module: ").append(Build.MODEL).append("\r\n");
		builder.append("Product: ").append(Build.PRODUCT).append("\r\n");
		builder.append("Software: (").append(Build.VERSION.SDK_INT).append(") ").append(Build.VERSION.RELEASE).append("\r\n");
		builder.append("-----------------\r\n");
		
		return builder.toString();
	}
}
