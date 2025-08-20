package com.tyron.code;

import android.os.Bundle;
import android.view.KeyEvent;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import com.tyron.code.ui.main.HomeFragment;
import org.codeassist.unofficial.R;
import android.os.PowerManager;
import android.provider.Settings;
import android.net.Uri;

public class MainActivity extends AppCompatActivity {
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.main);

   requestIgnoreBatteryOptimizations(); WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

    HomeFragment homeFragment = new HomeFragment();
    if (getSupportFragmentManager().findFragmentByTag(HomeFragment.TAG) == null) {
      getSupportFragmentManager()
          .beginTransaction()
          .replace(R.id.fragment_container, homeFragment, HomeFragment.TAG)
          .commit();
    }
  }

  @Override
  public void onSaveInstanceState(@NonNull Bundle outState) {
    super.onSaveInstanceState(outState);
  }

  @Override
  public boolean onKeyShortcut(int keyCode, KeyEvent event) {
    return super.onKeyShortcut(keyCode, event);
  }

private void requestIgnoreBatteryOptimizations() {
    PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
    if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    return super.onKeyDown(keyCode, event);
  }

  @Override
  public boolean onKeyUp(int keyCode, KeyEvent event) {
    return super.onKeyUp(keyCode, event);
  }
}
