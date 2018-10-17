package org.thoughtcrime.securesms.preferences;


import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.support.annotation.Nullable;
import android.support.v4.app.DialogFragment;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.bumptech.glide.load.engine.DiskCacheStrategy;

import org.thoughtcrime.securesms.ApplicationPreferencesActivity;
import org.thoughtcrime.securesms.BaseActionBarActivity;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.mms.GlideApp;
import org.thoughtcrime.securesms.util.ServiceUtil;
import org.thoughtcrime.securesms.util.TextSecurePreferences;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutionException;

public class ChatBackgroundDialogFragment extends BaseActionBarActivity {

    Button galleryButton;
    Button defaultButton;
    FrameLayout layoutContainer;
    MenuItem acceptMenuItem;

    String tempDestinationPath;
    Uri imageUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.select_chat_background_dialog);

        defaultButton = findViewById(R.id.set_default_button);
        galleryButton = findViewById(R.id.from_gallery_button);
        layoutContainer = findViewById(R.id.layout_container);

        defaultButton.setOnClickListener(new DefaultClickListener());
        galleryButton.setOnClickListener(new GalleryClickListener());

        String backgroundImagePath = TextSecurePreferences.getBackgroundImagePath(this);
        setLayoutBackgroundImage(backgroundImagePath);

        ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setHomeAsUpIndicator(R.drawable.ic_close_white_24dp);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuInflater inflater = this.getMenuInflater();
        menu.clear();
        inflater.inflate(R.menu.group_create, menu);
        acceptMenuItem = menu.findItem(R.id.menu_create_group);
        acceptMenuItem.setEnabled(false);
        super.onPrepareOptionsMenu(menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.menu_create_group) {
            // handle confirmation button click here
            Context context = getApplicationContext();
            if(imageUri != null){
                Thread thread = new Thread(){
                    @Override
                    public void run() {
                        String destination = context.getFilesDir().getAbsolutePath() + "/background";
                        scaleAndSaveImage(context, destination);
                        TextSecurePreferences.setBackgroundImagePath(context, destination);
                    }
                };
                thread.start();
            }
            else {
                TextSecurePreferences.setBackgroundImagePath(context, "");
            }
            finish();
            return true;
        } else if (id == android.R.id.home) {
            // handle close button click here
            finish();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void scaleAndSaveImage(Context context, String destinationPath) {
        try{
            Display display = ServiceUtil.getWindowManager(context).getDefaultDisplay();
            Point size = new Point();
            display.getSize(size);
            Bitmap scaledBitmap = GlideApp.with(context)
                    .asBitmap()
                    .load(imageUri)
                    .centerCrop()
                    .skipMemoryCache(true)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .submit(size.x, size.y)
                    .get();
            FileOutputStream outStream = new FileOutputStream(destinationPath);
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outStream);
        } catch (InterruptedException e) {
            e.printStackTrace();
            showBackgroundSaveError();
        } catch (ExecutionException e) {
            e.printStackTrace();
            showBackgroundSaveError();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            showBackgroundSaveError();
        }
    }

    private void setLayoutBackgroundImage(String backgroundImagePath) {
        Drawable image = Drawable.createFromPath(backgroundImagePath);
        layoutContainer.setBackground(image);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        final Context context = getApplicationContext();
        if (data != null && context != null && resultCode == RESULT_OK && requestCode == ApplicationPreferencesActivity.REQUEST_CODE_SET_BACKGROUND) {
            imageUri = data.getData();
            if (imageUri != null) {
                acceptMenuItem.setEnabled(true);
                Thread thread = new Thread(){
                    @Override
                    public void run() {
                        tempDestinationPath = context.getFilesDir().getAbsolutePath() + "/background-temp";
                        scaleAndSaveImage(context, tempDestinationPath);
                        runOnUiThread(() -> {
                            // Stuff that updates the UI
                            setLayoutBackgroundImage(tempDestinationPath);
                        });
                    }
                };
                thread.start();
            }
        }
    }

    private void showBackgroundSaveError() {
        Toast.makeText(this, R.string.AppearancePreferencesFragment_background_save_error, Toast.LENGTH_LONG).show();
    }

    private void enableMenuItem() {
        acceptMenuItem.setEnabled(true);
    }
    private class DefaultClickListener implements View.OnClickListener {
        @Override
        public void onClick(View view) {
            imageUri = null;
            tempDestinationPath = "";
            setLayoutBackgroundImage("");
            enableMenuItem();
        }

    }

    private class GalleryClickListener implements View.OnClickListener {
        @Override
        public void onClick(View view) {
          Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
          intent.addCategory(Intent.CATEGORY_OPENABLE);
          intent.setType("image/*");
          startActivityForResult(intent, ApplicationPreferencesActivity.REQUEST_CODE_SET_BACKGROUND);
        }
    }
}