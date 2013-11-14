/*   Copyright (c) 2013 Maxim Kovalev, Carnegie Mellon University
 *   This file is part of CMU SV Locator.
 *
 *   CMU SV Locator is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   CMU SV Locator is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with CMU SV Locator.  If not, see <http://www.gnu.org/licenses/>.
 */


package edu.sv.cmu.cmusvlocator;

import java.lang.reflect.Type;
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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.view.Menu;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
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

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

public class MainActivity extends Activity {
	
	// UI elements
	public EditText server_uri_ET, create_location_ET;
	public Button server_uri_apply_B, create_location_apply_B,
					suggested_location_accept_B;
	public Spinner select_location_S;
	public ToggleButton toggle_listening_TB, toggle_sending_TB, toggle_recording_TB;
	public TextView packets_sent_TV, suggested_location_TV, packets_pending_TV,
					server_response_TV, wifi_TV, gps_TV, current_location_TV,
					select_location_accept_B;
	
	//Hardcoded settings
	public Integer maximum_http_treads = 1;
	public Boolean location_list_request = true;
//	public String server_uri = "http://curie.cmu.sv.local:8080/api/v1/process_wifi_and_gps_reading";
//	public String server_uri = "http://10.0.23.67:8080/api/v1/process_wifi_gps_reading/";
	public String server_host_port = "maxwell.sv.cmu.local:8080";
	public String send_reading_path = "/api/v1/process_wifi_gps_reading/";
	public String send_reading_get_list = "/api/v1/process_wifi_gps_reading/list/";
	
	//Semaphore for HTTP sending threads
	public Semaphore http_semaphore;
	
	//Stats
	public Integer packets_sent = 0;
	
	//Flags
	Boolean scanning_allowed = true;
	Boolean sending_allowed = true;
	Boolean recording_allowed = false;
	
	//State
	Double Lon = null, Lat = null;
	String location_name = "", server_response = "", last_wifi_update = "",
			suggested_location = "", spinner_selected_location = "";
	List<ScanResult> wifipoints = null;
	LocationNameId received_location = null;
	List<LocationNameId> recv_loc_list = null;
	String spinner_array[];
	
	
	LocationListener gpslistener;
	LocationManager locman;
	WifiManager wifimanager;
	Context context;
	onWiFiScanAvailable wifi_receiver = null;

	
	
	
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		
		findUIElements();
		server_uri_ET.setText(server_host_port);
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
		select_location_S.setOnItemSelectedListener(new onSpinnerItemSelected());
		toggle_listening_TB = (ToggleButton)findViewById(R.id.toggle_listening_TB);
		toggle_recording_TB = (ToggleButton)findViewById(R.id.toggle_recording_TB);
		toggle_sending_TB = (ToggleButton)findViewById(R.id.toggle_sending_TB);
		packets_sent_TV = (TextView)findViewById(R.id.packets_sent_TV);	
		suggested_location_TV = (TextView)findViewById(R.id.suggested_location_TV);
		packets_pending_TV = (TextView)findViewById(R.id.packets_pending_TV);
		server_response_TV = (TextView)findViewById(R.id.server_response_TV);
		toggle_sending_TB.setOnClickListener(new onToggleSendingClicked());
		toggle_listening_TB.setOnClickListener(new onToggleScanningClicked());
		toggle_recording_TB.setOnClickListener(new onToggleRecordingClicked());

		server_uri_apply_B.setOnClickListener(new onServerURIApplyClicked());
		gps_TV = (TextView)findViewById(R.id.gps_TV);
		wifi_TV = (TextView)findViewById(R.id.wifi_TV);
		current_location_TV = (TextView)findViewById(R.id.current_location_TV);
		select_location_accept_B = (Button)findViewById(R.id.select_location_accept_B);
		suggested_location_accept_B.setOnClickListener(new onSuggestedLocationAcceptClicked());
		select_location_accept_B.setOnClickListener(new onSelectLocationAcceptClicked());
		create_location_apply_B.setOnClickListener(new onCreateLocationClicked());
	}

	public void startScanning() {
		scanning_allowed = true;
		gpslistener = new MyGPSListener();
		locman = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
		locman.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, gpslistener);
		context = getBaseContext();
        wifimanager = (WifiManager)context.getSystemService(Context.WIFI_SERVICE);
        if (wifi_receiver == null) {
        	wifi_receiver = new onWiFiScanAvailable();
        }
        registerReceiver(wifi_receiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
        wifimanager.startScan();
	}
	
	public void stopScanning() {
		scanning_allowed = false;
		locman.removeUpdates(gpslistener);
		unregisterReceiver(wifi_receiver);
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
		if (suggested_location != "") {
			suggested_location_TV.setText("Suggested location:\n" + suggested_location);
		}
		if (location_name != "") {
			current_location_TV.setText("Current location: " + location_name);
		}
		if (recv_loc_list != null && recv_loc_list.size() != 0) {
			spinner_array = new String[recv_loc_list.size()];
			int selected_pos = 0;			
			for(int i = 0; i < recv_loc_list.size(); i++) {
				String loc = recv_loc_list.get(i).location_name;
				spinner_array[i] = loc;
				if(spinner_selected_location != "" && spinner_selected_location.equals(loc)) {
					selected_pos = i;
				}
			}
			String tmp = spinner_array[selected_pos];
			spinner_array[selected_pos] = spinner_array[0];
			spinner_array[0] = tmp;
			ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
			        android.R.layout.simple_spinner_item, spinner_array);
			select_location_S.setAdapter(adapter);
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
			updateGUI();
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
			updateGUI();
		}
    	
    }
    
    class onToggleRecordingClicked implements OnClickListener {

		@Override
		public void onClick(View v) {
			ToggleButton b = (ToggleButton)v;
			if (b.isChecked()) {
				recording_allowed = true;
			}
			else {
				recording_allowed = false;
			}
			updateGUI();
		}
    	
    }

    class onServerURIApplyClicked implements OnClickListener {

		@Override
		public void onClick(View v) {
			server_host_port = server_uri_ET.getText().toString();
			updateGUI();
		}
    	
    }
    
    class onCreateLocationClicked implements OnClickListener {

		@Override
		public void onClick(View v) {
			location_name = create_location_ET.getText().toString();
			updateGUI();
		}
    	
    }

    class onSuggestedLocationAcceptClicked implements OnClickListener {

		@Override
		public void onClick(View v) {
			if (received_location != null) {
				location_name = received_location.location_name;
			}
			updateGUI();
		}
    	
    }    
    
    class onSelectLocationAcceptClicked implements OnClickListener {

		@Override
		public void onClick(View v) {
			if (spinner_selected_location != "") {
				location_name = spinner_selected_location;
			}
			updateGUI();
		}
    	
    } 
    
    class onSpinnerItemSelected implements OnItemSelectedListener {

		@Override
		public void onItemSelected(AdapterView<?> parent, View view, int pos,
				long id) {
			spinner_selected_location = parent.getItemAtPosition(pos).toString();
			// TODO Auto-generated method stub
			
		}

		@Override
		public void onNothingSelected(AdapterView<?> arg0) {
			// TODO Auto-generated method stub
			
		}
    	
    }
    
	public void trySendData() {
		if( (Lon != null && Lat != null) || wifipoints != null) {
			long unixTime = System.currentTimeMillis();
			String json_str = "{\"timestamp\": " +  Long.toString(unixTime);
			if (location_name != "" && recording_allowed) {
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
	
	
	class onWiFiScanAvailable extends BroadcastReceiver {

		@Override
		public void onReceive(Context context, Intent intent) {
			wifipoints = wifimanager.getScanResults();
			SimpleDateFormat sdf = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss.SSS");
			Date dt = new Date();
			last_wifi_update = sdf.format(dt);
			wifimanager.startScan();
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
			String server_uri = "http://" + server_host_port;
			if (location_list_request) {
				server_uri += send_reading_get_list;
			} else {
				server_uri += send_reading_path;
			}
			HttpPost postMethod = new HttpPost(server_uri);
			postMethod.addHeader("content-type", "application/json");
			try {
				postMethod.setEntity(new StringEntity(to_send, "UTF-8"));
				HttpResponse response = client.execute(postMethod);
				resp = response.getStatusLine().getReasonPhrase();
				publishProgress(resp);
				return true;
			} catch (Exception e) {
				publishProgress(e.toString());
				return false;
			} 
		}
		
		protected void onProgressUpdate(Object... params) {
			String str = (String)params[0];
			server_response = str;
			try {
				if (location_list_request) {
					Type collectionType = new TypeToken<List<LocationNameId>>(){}.getType();
					recv_loc_list = new Gson().fromJson(str, collectionType);
					if (recv_loc_list != null && recv_loc_list.size() > 0) {
						received_location = recv_loc_list.get(0);
						suggested_location = received_location.location_name;
					}
				} else {
					received_location = new Gson().fromJson(str, LocationNameId.class);
					suggested_location = received_location.location_name;
				}
			} catch (Exception e) {
				server_response += "\n" + e.toString();
			}
		}
		
		protected void onPostExecute(Boolean param) {
			http_semaphore.release();
			if (param) {
				packets_sent += 1;
			}

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
	
	public class LocationNameId {
		public Integer location_id;
		public String location_name;
	}
	
}
