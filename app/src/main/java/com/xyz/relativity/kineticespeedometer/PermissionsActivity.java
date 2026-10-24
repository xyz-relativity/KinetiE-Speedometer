package com.xyz.relativity.kineticespeedometer;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

public class PermissionsActivity extends AppCompatActivity {

	private static final int PERMISSIONS_REQUEST_LOCATION = 111;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_permissions);

		// Android 12+ ignores a request for fine location alone; both must be asked for together.
		ActivityCompat.requestPermissions(this,
				new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
				PERMISSIONS_REQUEST_LOCATION);
	}

	@Override
	public void onRequestPermissionsResult(int requestCode,
	                                       String[] permissions, int[] grantResults) {
		switch (requestCode) {
			case PERMISSIONS_REQUEST_LOCATION: {
				// If the request is cancelled, the result arrays are empty. Either grant is enough:
				// precise unlocks the GPS provider, approximate the fused or network one.
				for (int result : grantResults) {
					if (result == PackageManager.PERMISSION_GRANTED) {
						finish();
						return;
					}
				}
				// Denied: the app cannot measure speed without a location, so stay on this screen.
				return;
			}

			// other 'case' lines to check for other
			// permissions this app might request.
		}
	}

}
