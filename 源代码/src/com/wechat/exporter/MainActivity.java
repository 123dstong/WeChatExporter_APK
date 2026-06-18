package com.wechat.exporter;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private TextView tvLog, tvRootStatus;
    private ScrollView scrollView;
    private EditText etPath;
    private Button btnExport;
    private boolean hasRoot = false;

    private static final String EXPORT_DIR =
            Environment.getExternalStorageDirectory().getAbsolutePath() + "/WeChatExporter";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvLog = findViewById(R.id.tvLog);
        tvRootStatus = findViewById(R.id.tvRootStatus);
        scrollView = findViewById(R.id.scrollView);
        etPath = findViewById(R.id.etPath);
        btnExport = findViewById(R.id.btnExport);

        btnExport.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startExport();
            }
        });

        requestStoragePermission();
        checkRoot();
    }

    private void requestStoragePermission() {
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            List<String> perms = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
            if (!perms.isEmpty()) {
                requestPermissions(perms.toArray(new String[0]), 100);
            }
        }
    }

    private void checkRoot() {
        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... voids) {
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", "su -c id"});
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(p.getInputStream()));
                    String line = reader.readLine();
                    p.waitFor();
                    return line != null && line.contains("uid=0");
                } catch (Exception e) {
                    return false;
                }
            }

            @Override
            protected void onPostExecute(Boolean rooted) {
                hasRoot = rooted;
                tvRootStatus.setText(rooted ? "已获取ROOT权限 ✓" : "未检测到ROOT权限 ✗");
                tvRootStatus.setTextColor(rooted ? 0xFF07C160 : 0xFFE53935);
                log("Root检测: " + (rooted ? "已获取ROOT权限 ✓" : "未检测到ROOT权限"));
                if (!rooted) {
                    log("⚠ 无Root权限将无法读取微信私有数据");
                    log("  请确保设备已Root并授权Superuser");
                }
            }
        }.execute();
    }

    private void startExport() {
        btnExport.setEnabled(false);
        btnExport.setText("导出中...");
        log("═══════════════════════════════");
        log("开始导出聊天记录...");

        new AsyncTask<Void, String, Boolean>() {
            String resultMsg = "";

            @Override
            protected Boolean doInBackground(Void... voids) {
                try {
                    // Find WeChat path
                    String wechatPath = etPath.getText().toString().trim();
                    if (wechatPath.isEmpty()) {
                        wechatPath = findWeChatPath();
                    }
                    if (wechatPath == null) {
                        publishProgress("错误: 无法找到微信数据路径");
                        return false;
                    }
                    publishProgress("微信路径: " + wechatPath);

                    // Create export dir
                    File exportDir = new File(EXPORT_DIR);
                    if (!exportDir.exists()) exportDir.mkdirs();
                    publishProgress("导出目录: " + EXPORT_DIR);

                    // Find all .db files
                    publishProgress("扫描数据库...");
                    List<String> dbFiles = execSu("find " + wechatPath +
                            " -name '*.db' -size +0c 2>/dev/null");
                    publishProgress("找到 " + dbFiles.size() + " 个数据库");

                    int exported = 0;
                    for (String dbPath : dbFiles) {
                        dbPath = dbPath.trim();
                        if (dbPath.isEmpty()) continue;

                        String dbBase = dbPath.substring(dbPath.lastIndexOf('/') + 1);
                        publishProgress("处理: " + dbBase);

                        // Copy to sdcard
                        String tempDb = EXPORT_DIR + "/temp_" + dbBase;
                        execSu("cp '" + dbPath + "' '" + tempDb + "' && chmod 644 '" + tempDb + "'");

                        File tempFile = new File(tempDb);
                        if (tempFile.exists() && tempFile.length() > 0) {
                            String outName = EXPORT_DIR + "/" +
                                    dbBase.replace(".db", "") + "_" +
                                    new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                                            .format(new Date()) + ".txt";

                            exportDb(tempDb, outName, dbBase, dbPath);
                            exported++;
                            new File(tempDb).delete();
                        }
                    }

                    resultMsg = "导出完成!\n成功: " + exported + " 个数据库\n目录: " + EXPORT_DIR;
                    publishProgress(resultMsg);
                    return true;

                } catch (Exception e) {
                    resultMsg = "导出失败: " + e.getMessage();
                    publishProgress(resultMsg);
                    return false;
                }
            }

            @Override
            protected void onProgressUpdate(String... values) {
                if (values.length > 0) log(values[0]);
            }

            @Override
            protected void onPostExecute(Boolean success) {
                btnExport.setEnabled(true);
                btnExport.setText("开始导出聊天记录");
                if (success) {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("导出完成")
                            .setMessage(resultMsg)
                            .setPositiveButton("确定", null)
                            .show();
                }
            }
        }.execute();
    }

    private String findWeChatPath() {
        log("自动检测微信路径...");

        // Check common WeChat MicroMsg paths
        String[] candidates = {
            "/data/data/com.tencent.mm/MicroMsg",
            "/data/user/0/com.tencent.mm/MicroMsg"
        };

        for (String path : candidates) {
            List<String> r = execSu("ls '" + path + "' 2>/dev/null");
            if (!r.isEmpty() && !r.get(0).contains("No such file")) {
                log("找到: " + path);
                return path;
            }
        }

        // Broader search
        List<String> r = execSu(
                "find /data/data/com.tencent.mm -maxdepth 2 -name 'MicroMsg' -type d 2>/dev/null");
        for (String s : r) {
            s = s.trim();
            if (!s.isEmpty() && !s.startsWith("find:")) {
                log("找到: " + s);
                return s;
            }
        }

        return null;
    }

    private void exportDb(String dbPath, String outPath, String dbName, String origPath) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("═══════════════════════════════\n");
            sb.append("数据库: ").append(dbName).append("\n");
            sb.append("原始路径: ").append(origPath).append("\n");
            sb.append("导出时间: ").append(
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                            .format(new Date())).append("\n");
            sb.append("═══════════════════════════════\n\n");

            // Get table list
            List<String> tables = execSu("sqlite3 '" + dbPath + "' '.tables'");
            StringBuilder allTables = new StringBuilder();
            for (String t : tables) allTables.append(t).append(" ");
            String[] tableNames = allTables.toString().trim().split("\\s+");

            for (String table : tableNames) {
                table = table.trim();
                if (table.isEmpty()) continue;

                sb.append("──── 表: ").append(table).append(" ────\n");

                // Get columns
                List<String> cols = execSu("sqlite3 '" + dbPath +
                        "' \"PRAGMA table_info(" + table + ");\" | cut -d'|' -f2");
                StringBuilder colLine = new StringBuilder();
                for (String c : cols) {
                    if (!c.trim().isEmpty()) colLine.append(c.trim()).append(" | ");
                }
                if (colLine.length() > 3) {
                    sb.append("列: ").append(colLine.substring(0, colLine.length() - 3)).append("\n");
                }

                // Get row count
                List<String> countResult = execSu("sqlite3 '" + dbPath +
                        "' \"SELECT COUNT(*) FROM " + table + ";\"");
                String countStr = countResult.isEmpty() ? "0" : countResult.get(0).trim();
                sb.append("行数: ").append(countStr).append("\n\n");

                // Dump data (limit 200 rows per table)
                List<String> rows = execSu("sqlite3 -header -column '" + dbPath +
                        "' \"SELECT * FROM " + table + " LIMIT 200;\"");
                for (String row : rows) {
                    sb.append(row).append("\n");
                }
                sb.append("\n");
            }

            FileOutputStream fos = new FileOutputStream(outPath);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();

            log("  ✓ 导出: " + outPath.substring(outPath.lastIndexOf('/') + 1));

        } catch (Exception e) {
            log("  ✗ 错误: " + e.getMessage());
        }
    }

    private List<String> execSu(String command) {
        List<String> output = new ArrayList<>();
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"sh"});
            DataOutputStream os = new DataOutputStream(process.getOutputStream());
            os.writeBytes("su -c '" + command + "'\n");
            os.writeBytes("exit\n");
            os.flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                output.add(line);
            }
            process.waitFor();
        } catch (Exception e) {
            output.add("ERROR: " + e.getMessage());
        }
        return output;
    }

    private void log(String msg) {
        if (tvLog != null) {
            String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    .format(new Date());
            tvLog.append("[" + time + "] " + msg + "\n");
            scrollView.post(new Runnable() {
                @Override
                public void run() {
                    scrollView.fullScroll(View.FOCUS_DOWN);
                }
            });
        }
    }
}
