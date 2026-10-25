package com.xyz.relativity.kineticespeedometer;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/** Explains why location is needed and asks for it; offers system settings once it was refused for good. */
public class PermissionsActivity extends AppCompatActivity {
	private static final int REQUEST_LOCATION = 111;

	private TextView bodyView;
	private Button openSettingsButton;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_permissions);
		bodyView = findViewById(R.id.permissionBody);
		openSettingsButton = findViewById(R.id.buttonOpenSettings);

		findViewById(R.id.buttonAllow).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				ActivityCompat.requestPermissions(PermissionsActivity.this,
						new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_LOCATION);
			}
		});
		openSettingsButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
						Uri.fromParts("package", getPackageName(), null));
				startActivity(intent);
			}
		});
	}

	@Override
	protected void onResume() {
		super.onResume();
		if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
			finish();
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if (requestCode != REQUEST_LOCATION) {
			return;
		}
		if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
			finish();
			return;
		}
		boolean canAskAgain = ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_FINE_LOCATION);
		bodyView.setText(canAskAgain ? R.string.permission_denied : R.string.permission_denied_permanently);
		openSettingsButton.setVisibility(canAskAgain ? View.GONE : View.VISIBLE);
	}
}
