package ng.com.nhub.paygis;

import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.content.FileProvider;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;

import ng.com.nhub.paygis.address.Work;
import ng.com.nhub.paygis.etc.AppData;
import ng.com.nhub.paygis.etc.BuildVars;
import ng.com.nhub.paygis.lib.FileLog;
import ng.com.nhub.paygis.lib.LocaleController;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class WorkActivity extends AppCompatActivity implements View.OnClickListener {

    final int QRCODE_SIZE = 500; //TODO: take value from res/values and use it too in content_home.xml for imageview

    private boolean startPressed = false;
    private ProgressDialog progressDialog;
    private AlertDialog visibleDialog = null;

    ImageView qrcodeView;
    FloatingActionButton fab;

    public static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private final OkHttpClient client = new OkHttpClient();

    TextView getShortCodeTextView;
    TextView shortCodeTextView;

    private Boolean importantFlag = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        setContentView(R.layout.activity_work);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(this);

        qrcodeView = (ImageView) findViewById(R.id.qrcode);
        getShortCodeTextView = (TextView) findViewById(R.id.textshortcode);
        getShortCodeTextView.setVisibility(View.GONE);
        shortCodeTextView = (TextView) findViewById(R.id.shortcode);
        shortCodeTextView.setVisibility(View.GONE);

        if(AppData.getWork().traceCode != null){
            loadUI();
        }else{
            loadLogicalAddressDetails();
            fab.setVisibility(View.GONE);
        }

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getShortCodeTextView.setOnClickListener(this);
    }

    private void getShortCode(){

        showProgress();

        try{

            Request request = new Request.Builder()
                    .url(BuildVars.ServerRootPath + "/api/v1/shortcode/" + AppData.getWork().traceCode)
                    .header("User-Agent", "Android")
                    .addHeader("x-auth-token", AppData.getCurrentUser().accessToken)
                    .get()
                    .build();

            client.newCall(request).enqueue(new Callback() {

                @Override
                public void onFailure(Call call, IOException e) {

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            hideProgress();
                            needShowAlert(LocaleController.getString("general_error", R.string.general_error));
                        }
                    });
                    e.printStackTrace();
                    FileLog.e("tmessages", "okHttp Fatal" + e);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            hideProgress();
                        }
                    });

                    if (response.code() == HttpURLConnection.HTTP_OK) {

                        String strResponse = response.body().string();

                        try {
                            JSONObject JSONresponse = new JSONObject(strResponse);
                            Work logicalAddress = AppData.getWork();
                            JSONObject details = JSONresponse.getJSONObject("data");
                            logicalAddress.shortcode = details.getString("short_code");

                            AppData.setWorkAddress(logicalAddress);
                            AppData.saveWorkAddress(true);

                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    postCreateAction(); //TODO: Pub Sub
                                }
                            });

                        } catch (JSONException e) {
                            e.printStackTrace();
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    needShowAlert(LocaleController.getString("unknown_error", R.string.unknown_error));
                                }
                            });
                        }

                    } else if (response.code() == HttpURLConnection.HTTP_NOT_FOUND) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                importantFlag = !importantFlag;
                                postCreateAction();//No Record found, is not an error
                            }
                        });
                    }else if (response.code() == HttpURLConnection.HTTP_BAD_REQUEST) {
                        //TODO: To Fire Log Out Event
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                needShowAlert(LocaleController.getString("session_expired", R.string.session_expired));
                            }
                        });
                    }  else {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                needShowAlert(LocaleController.getString("try_again", R.string.try_again));
                            }
                        });
                    }
                    /*
                    http://stackoverflow.com/questions/31040310/okhttp-get-failed-response-body
                    Actually, on okhttp 2.4.0, when you call response.body().string() twice, the second
                    call breaks execution with java.lang.IllegalStateException: closed
                     */
                }
            });

        }catch (Exception e){

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    hideProgress();
                    needShowAlert(LocaleController.getString("unknown_error", R.string.unknown_error));
                }
            });
            FileLog.e("tmessages", "okHttp failed to deliver request");
            e.printStackTrace();
        }

    }

    private void requestShortCode(){

        showProgress();

        try{

            JSONObject messageJson = new JSONObject();
            messageJson.put("trace_id", AppData.getWork().traceCode);

            RequestBody body = RequestBody.create(JSON, messageJson.toString());
            Request request = new Request.Builder()
                    .url(BuildVars.ServerRootPath + "/api/v1/shortcode")
                    .header("User-Agent", "Android")
                    .addHeader("x-auth-token", AppData.getCurrentUser().accessToken)
                    .post(body)
                    .build();

            client.newCall(request).enqueue(new Callback() {

                @Override
                public void onFailure(Call call, IOException e) {

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            hideProgress();
                            needShowAlert(LocaleController.getString("general_error", R.string.general_error));
                        }
                    });

                    e.printStackTrace();
                    FileLog.e("tmessages", "okHttp Fatal" + e);
                    startPressed = false;
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            hideProgress();
                        }
                    });

                    String strResponse = response.body().string();

                    if (response.code() == HttpURLConnection.HTTP_OK) {

                        try {

                            JSONObject JSONresponse = new JSONObject(strResponse);

                            Work logicalAddress = AppData.getWork();
                            JSONObject details = JSONresponse.getJSONObject("data");
                            logicalAddress.shortcode = details.getString("short_code");

                            AppData.setWorkAddress(logicalAddress);
                            AppData.saveWorkAddress(true);

                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    postCreateAction(); //TODO: Pub Sub
                                }
                            });

                        } catch (JSONException e) {

                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    needShowAlert(LocaleController.getString("unknown_error", R.string.unknown_error));
                                }
                            });

                            e.printStackTrace();
                        }

                    } else if ((response.code() == HttpURLConnection.HTTP_BAD_REQUEST ||
                            response.code() == HttpURLConnection.HTTP_UNAUTHORIZED) ) {

                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                needShowAlert(LocaleController.getString("session_expired", R.string.session_expired));
                            }
                        });

                    } else {

                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                needShowAlert(LocaleController.getString("try_again", R.string.try_again));
                            }
                        });

                    }
                    startPressed = false;
                }
            });

        }catch (Exception e){

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    hideProgress();
                    needShowAlert(LocaleController.getString("unknown_error", R.string.unknown_error));
                }
            });

            FileLog.e("tmessages", "okHttp failed to deliver request");
            e.printStackTrace();
            startPressed = false;
        }
    }

    class ProcessQRCode extends AsyncTask<String, Void, Bitmap> {
        /** The system calls this to perform work in a worker thread and
         * delivers it the parameters given to AsyncTask.execute() */
        protected Bitmap doInBackground(String... params) {

            Bitmap bitmap = encodeToQrCode(params[0], QRCODE_SIZE, QRCODE_SIZE);
            // save bitmap to cache directory
            try {
                File cachePath = new File(getApplicationContext().getCacheDir(), "images");
                cachePath.mkdirs(); // don't forget to make the directory
                FileOutputStream stream = new FileOutputStream(cachePath + "/work.png"); // overwrites this image every time
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
                stream.close();
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }

            return bitmap;
        }

        /** The system calls this to perform work in the UI thread and delivers
         * the result from doInBackground() */
        protected void onPostExecute(Bitmap bp) {
            qrcodeView.setImageBitmap(bp);
        }
    }

    private void loadUI(){
        new ProcessQRCode().execute(AppData.getWork().traceCode);
        //qrcodeView.setImageBitmap(encodeToQrCode(AppData.getWork().traceCode, QRCODE_SIZE, QRCODE_SIZE));
        fab.setVisibility(View.VISIBLE);

        if(AppData.getWork().shortcode == null){
            //shortCodeTextView.setVisibility(View.GONE); //Done in .xml/Not sure which is best
            // getShortCodeTextView.setVisibility(View.GONE);
            //The visibility here depends on weather the user has previously created it or not
            if(importantFlag){
                getShortCode();
            }else{
                getShortCodeTextView.setVisibility(View.VISIBLE);
                shortCodeTextView.setVisibility(View.GONE);
            }
        }else{
            shortCodeTextView.setVisibility(View.VISIBLE);
            shortCodeTextView.setText(AppData.getWork().shortcode);
            getShortCodeTextView.setVisibility(View.GONE);
        }
    }

    protected void onActivityResult(int requestCode, int resultCode, Intent data) {

        if (requestCode == 1 && data != null) {

            if(data.getBooleanExtra("updated", false)){
                loadUI();
            }else{
                //TODO: Inform user of their actions
                finish();
            }
        }else{
            //TODO: Inform user of their actions
            finish();
        }
    }

    private void postCreateAction(){
        loadUI();
    }

    private void loadLogicalAddressDetails(){

        showProgress();

        try{

            Request request = new Request.Builder()
                    .url(BuildVars.ServerRootPath + "/api/v1/work")
                    .header("User-Agent", "Android")
                    .addHeader("x-auth-token", AppData.getCurrentUser().accessToken)
                    .get()
                    .build();

            client.newCall(request).enqueue(new Callback() {

                @Override
                public void onFailure(Call call, IOException e) {

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            hideProgress();
                            needShowAlert(LocaleController.getString("general_error", R.string.general_error));
                        }
                    });
                    e.printStackTrace();
                    FileLog.e("tmessages", "okHttp Fatal" + e);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            hideProgress();
                        }
                    });

                    if (response.code() == HttpURLConnection.HTTP_OK) {

                        String strResponse = response.body().string();


                        try {
                            JSONObject JSONresponse = new JSONObject(strResponse);

                            JSONObject data = JSONresponse.getJSONObject("data");
                            String createdAt = data.getString("created_at");
                            String updatedAt = data.getString("updated_at");

                            if(createdAt.equals(updatedAt)){

                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        Intent theIntent = new Intent(getApplicationContext(), WorkRegisterActivity.class);
                                        startActivityForResult(theIntent, 1);
                                    }
                                });

                            }else{
                                final Work addressDetails = new Work();
                                addressDetails.title = data.getString("work_name");
                                addressDetails.address = data.getString("address");
                                addressDetails.city = data.getString("city");
                                addressDetails.traceCode = data.getString("trace_id");
                                JSONObject locationObj = data.getJSONObject("location_ref");
                                addressDetails.longitude = locationObj.getJSONObject("gps").getDouble("longitude");
                                addressDetails.latitude = locationObj.getJSONObject("gps").getDouble("latitude");
                                AppData.setWorkAddress(addressDetails);
                                AppData.saveWorkAddress(true);
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        postCreateAction(); //TODO: Pub Sub
                                    }
                                });
                            }

                        } catch (JSONException e) {
                            e.printStackTrace();
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    needShowAlert(LocaleController.getString("unknown_error", R.string.unknown_error));
                                }
                            });
                        }

                    } else if (response.code() == HttpURLConnection.HTTP_NOT_FOUND) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
//                                needShowAlert("Something Wicked Happened. Try again later");
                                needShowAlert(LocaleController.getString("try_again", R.string.try_again));
                            }
                        });
                    }else if (response.code() == HttpURLConnection.HTTP_BAD_REQUEST) {
                        //TODO: To Fire Log Out Event
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                needShowAlert(LocaleController.getString("session_expired", R.string.session_expired));
                            }
                        });
                    }  else {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                needShowAlert(LocaleController.getString("try_again", R.string.try_again));
                            }
                        });
                    }
                    /*
                    http://stackoverflow.com/questions/31040310/okhttp-get-failed-response-body
                    Actually, on okhttp 2.4.0, when you call response.body().string() twice, the second
                    call breaks execution with java.lang.IllegalStateException: closed
                     */
                }
            });

        }catch (Exception e){

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    hideProgress();
                    needShowAlert(LocaleController.getString("unknown_error", R.string.unknown_error));
                }
            });
            FileLog.e("tmessages", "okHttp failed to deliver request");
            e.printStackTrace();
        }
    }

    public Bitmap encodeToQrCode(String text, int width, int height){
        QRCodeWriter writer = new QRCodeWriter();
        BitMatrix matrix = null;
        try {
            matrix = writer.encode(text, BarcodeFormat.QR_CODE, width, width);
        } catch (WriterException ex) {
            ex.printStackTrace();
        }
        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
        for (int x = 0; x < width; x++){
            for (int y = 0; y < height; y++){
                bmp.setPixel(x, y, matrix.get(x,y) ? Color.BLACK : Color.WHITE);
            }
        }
        return bmp;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.work, menu);
        return true;
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.

        int id = item.getItemId();

        switch (id) {

            case R.id.action_show_location:{
                displayMapPreview();
                return true;
            }
            case R.id.action_edit_location:{
                Intent theIntent = new Intent(getApplicationContext(), WorkRegisterActivity.class);
                startActivityForResult(theIntent, 1);
                return true;
            }
            case R.id.action_share:{

                File imagePath = new File(getApplicationContext().getCacheDir(), "images");
                File newFile = new File(imagePath, "work.png");
                Uri contentUri = FileProvider.getUriForFile(getApplicationContext(), "ng.com.nhub.paygis.fileprovider", newFile);

                if (contentUri != null) {

                    Intent shareIntent = new Intent();
                    shareIntent.setAction(Intent.ACTION_SEND);
                    shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); // temp permission for receiving app to read this file
                    shareIntent.setDataAndType(contentUri, getContentResolver().getType(contentUri));
                    shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
                    shareIntent.putExtra(Intent.EXTRA_TEXT, LocaleController.getString("iLive", R.string.iWork));
                    shareIntent.putExtra(Intent.EXTRA_TITLE, LocaleController.getString("iLive", R.string.iWork));
                    shareIntent.putExtra(Intent.EXTRA_SUBJECT, LocaleController.getString("iLive", R.string.iWork));
                    startActivity(Intent.createChooser(shareIntent, LocaleController.getString("app_chooser", R.string.app_chooser)));

                }

                return true;
            }
        }
        return super.onOptionsItemSelected(item);
    }

    public void needShowAlert(final String text) {
        if (text == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(LocaleController.getString("app_name", R.string.app_name));
        builder.setMessage(text);
        builder.setPositiveButton(LocaleController.getString("OK", R.string.OK), null);
        showAlertDialog(builder);
    }

    public void showProgress() {
        if (this.isFinishing() || progressDialog != null) {
            return;
        }
        progressDialog = new ProgressDialog(this);
        progressDialog.setMessage(LocaleController.getString("Loading", R.string.Loading));
        progressDialog.setCanceledOnTouchOutside(false);
        progressDialog.setCancelable(false);
        progressDialog.show();
    }

    public void hideProgress() {
        if (progressDialog == null) {
            return;
        }
        try {
            progressDialog.dismiss();
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
        progressDialog = null;
    }

    private void showAlertDialog(AlertDialog.Builder builder) {

        try {
            visibleDialog = builder.show();
            visibleDialog.setCanceledOnTouchOutside(true);
            visibleDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface dialog) {
                    visibleDialog = null;
                    onDialogDismiss();
                }
            });
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
    }

    private void onDialogDismiss() {

    }

    private void displayMapPreview(){

        Intent mapIntent = new Intent(getApplicationContext(), MapPreviewActivity.class);
        mapIntent.putExtra("longitude", AppData.getWork().longitude);
        mapIntent.putExtra("latitude", AppData.getWork().latitude);
        startActivity(mapIntent);
    }

    @Override
    public void onClick(View view) {

        if(view.getId() == R.id.fab){
            displayMapPreview();
        }else if(view.getId() == R.id.textshortcode){

            if (startPressed) {
                return;
            }
            startPressed = true;
            requestShortCode();
        }

    }

}
