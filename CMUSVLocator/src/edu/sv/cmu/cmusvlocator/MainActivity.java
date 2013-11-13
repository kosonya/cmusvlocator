package edu.sv.cmu.cmusvlocator;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Semaphore;

import edu.sv.cmu.cmusvlocator.R;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.app.Activity;
import android.content.Context;
import android.view.Menu;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ToggleButton;

import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.HttpResponse;

public class MainActivity extends Activity {
	
	// UI elements
	public EditText server_uri_ET, create_location_ET;
	public Button server_uri_apply_B, create_location_apply_B,
					suggested_location_accept_B;
	public Spinner select_location_S;
	public ToggleButton toggle_listening_TB, toggle_sending_TB;
	public TextView packets_sent_TV, suggested_location_TV, packets_pending_TV,
					server_response_TV, wifi_TV, gps_TV;
	
	//Hardcoded settings
	public Integer maximum_http_treads = 1;
//	public String server_uri = "http://curie.cmu.sv.local:8080/api/v1/process_wifi_and_gps_reading";
	public String server_uri = "http://10.0.20.179:8080/api/v1/process_wifi_gps_reading/";

	//Semaphore for HTTP sending threads
	public Semaphore http_semaphore;
	
	//Stats
	public Integer packets_sent = 0;
	
	//Flags
	Boolean scanning_allowed = true;
	Boolean sending_allowed = false;
	
	//State
	Double Lon = null, Lat = null;
	String location_name = "", server_response = "", last_wifi_update = "";
	List<ScanResult> wifipoints = null;
	
	
	LocationListener gpslistener;
	LocationManager locman;
	WifiManager wifimanager;
	Context context;

	
	
	
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		
		findUIElements();
		server_uri_ET.setText(server_uri);
		toggle_listening_TB.setChecked(scanning_allowed);
		toggle_sending_TB.setChecked(sending_allowed);
		
		http_semaphore = new Semaphore(maximum_http_treads, true);
		if (scanning_allowed) {
			startScanning();
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.main, menu);
		return true;
	}
	
	
	public void findUIElements() {
		server_uri_ET = (EditText)findViewById(R.id.server_uri_ET);
		create_location_ET = (EditText)findViewById(R.id.create_location_ET);
		server_uri_apply_B = (Button)findViewById(R.id.server_uri_apply_B);
		create_location_apply_B = (Button)findViewById(R.id.create_location_B);
		suggested_location_accept_B = (Button)findViewById(R.id.suggested_location_accept_B);
		select_location_S = (Spinner)findViewById(R.id.select_location_S);
		toggle_listening_TB = (ToggleButton)findViewById(R.id.toggle_listening_TB);
		toggle_sending_TB = (ToggleButton)findViewById(R.id.toggle_sending_TB);
		packets_sent_TV = (TextView)findViewById(R.id.packets_sent_TV);	
		suggested_location_TV = (TextView)findViewById(R.id.suggested_location_TV);
		packets_pending_TV = (TextView)findViewById(R.id.packets_pending_TV);
		server_response_TV = (TextView)findViewById(R.id.server_response_TV);
		toggle_sending_TB.setOnClickListener(new onToggleSendingClicked());
		toggle_listening_TB.setOnClickListener(new onToggleScanningClicked());
		gps_TV = (TextView)findViewById(R.id.gps_TV);
		wifi_TV = (TextView)findViewById(R.id.wifi_TV);
	}

	public void startScanning() {
		scanning_allowed = true;
		gpslistener = new MyGPSListener();
		locman = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
		locman.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, gpslistener);
		context = getBaseContext();
        wifimanager = (WifiManager)context.getSystemService(Context.WIFI_SERVICE);
        AsyncTask<Object, List<ScanResult>, Object> wifiscan = new WiFiScan();
        wifiscan.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, (Object[])null);
	}
	
	public void stopScanning() {
		scanning_allowed = false;
		locman.removeUpdates(gpslistener);
	}
	
	public void onSomeUpdate() {
		updateGUI();
		if (sending_allowed) {
			trySendData();
		}
	}
	
	public void updateGUI() {
		packets_sent_TV.setText("Packets sent: " + Integer.toString(packets_sent));
		packets_pending_TV.setText("Packets pending: " + Integer.toString(maximum_http_treads - http_semaphore.availablePermits()));
		if (server_response != "") {
			server_response_TV.setText("Server response:\n" + server_response);
		}
		if (Lon != null && Lat != null) {
			gps_TV.setText("Longitude: " + Double.toString(Lon) + "; Latitude: " + Double.toString(Lat));
		}
		if (last_wifi_update != "") {
			wifi_TV.setText("Last WiFi update at: " + last_wifi_update);
		}
	}

    class onToggleSendingClicked implements OnClickListener {

		@Override
		public void onClick(View v) {
			ToggleButton b = (ToggleButton)v;
			if (b.isChecked()) {
				sending_allowed = true;
			}
			else {
				sending_allowed = false;
			}
		}
    	
    }

    class onToggleScanningClicked implements OnClickListener {

		@Override
		public void onClick(View v) {
			ToggleButton b = (ToggleButton)v;
			if (b.isChecked()) {
				startScanning();
			}
			else {
				stopScanning();
			}
		}
    	
    }
    
    
	public void trySendData() {
		if( (Lon != null && Lat != null) || wifipoints != null) {
			long unixTime = System.currentTimeMillis();
			String json_str = "{\"timestamp\": " +  Long.toString(unixTime);
			if (location_name != "") {
				json_str += ", \"location\": \"" + location_name + "\"";
			}
			if (Lon != null && Lat != null) {
				json_str += ", \"GPSLat\": " + Double.toString(Lat);
				json_str += ", \"GPSLon\": " + Double.toString(Lon);
			}
			if (wifipoints != null) {
				for (ScanResult scanres: wifipoints) {
					json_str += ", \"wifiBSSID" + scanres.BSSID + "\": " + Integer.toString(scanres.level);
				}
			}	
			json_str += "}";
			sendHTTPData(json_str);
		}
	}
	
	
	public class WiFiScan extends AsyncTask<Object, List<ScanResult>, Object> {

		@Override
		protected Object doInBackground(Object... params) {
			while (scanning_allowed) {
				wifimanager.startScan();
				List<ScanResult> res = wifimanager.getScanResults();
				publishProgress(res);
			}
			return null;
		}
		
		protected void onProgressUpdate(List<ScanResult>... params) {
			wifipoints = params[0];
			SimpleDateFormat sdf = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss.SSS");
			Date dt = new Date();
			last_wifi_update = sdf.format(dt);
			onSomeUpdate();
		}
		
	}
	
	
	public void sendHTTPData(String to_send) {
		if (http_semaphore.tryAcquire()) {
			//You gotta be kidding me!
			//http://stackoverflow.com/questions/9119627/android-sdk-asynctask-doinbackground-not-running-subclass
			AsyncTask<String, Object, Boolean> httpsender = new HTTPSender();
			String args[] = {to_send};
			httpsender.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, args);
		}
	}
	
	public class HTTPSender extends AsyncTask<String, Object, Boolean> {

		String resp = "";
		
		@Override
		protected Boolean doInBackground(String... params) {
			String to_send = params[0];
			HttpClient client = new DefaultHttpClient();
			HttpPost postMethod = new HttpPost(server_uri);
			postMethod.addHeader("content-type", "application/json");
			try {
				postMethod.setEntity(new StringEntity(to_send, "UTF-8"));
				HttpResponse response = client.execute(postMethod);
				resp = response.getStatusLine().getReasonPhrase();
				return true;
			} catch (Exception e) {
				resp = e.toString();
				return false;
			} 
		}
		
		protected void onPostExecute(Boolean param) {
			http_semaphore.release();
			if (param) {
				packets_sent += 1;
			}
			server_response = resp;

			updateGUI();
		}
		
	}
	
	
	public class MyGPSListener implements LocationListener {

		@Override
		public void onLocationChanged(Location location) {
			Lat = Double.valueOf(location.getLatitude());
			Lon = Double.valueOf(location.getLongitude());	
			onSomeUpdate();
		}

		@Override
		public void onProviderDisabled(String provider) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void onProviderEnabled(String provider) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void onStatusChanged(String provider, int status, Bundle extras) {
			// TODO Auto-generated method stub
			
		}
	}
	
	
}
