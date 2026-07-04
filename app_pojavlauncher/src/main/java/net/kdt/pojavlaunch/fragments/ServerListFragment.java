package net.kdt.pojavlaunch.fragments;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import net.kdt.pojavlaunch.instances.ServerInstance;
import net.kdt.pojavlaunch.instances.ServerInstance.ServerType;
import net.kdt.pojavlaunch.downloader.ServerJarDownloader;
import net.kdt.pojavlaunch.services.ServerForegroundService;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;

/**
 * Fragment that lists all server instances and provides Create / Start / Stop / Console / Delete actions.
 * Server processes are managed by ServerForegroundService so they survive UI lifecycle events.
 */
public class ServerListFragment extends Fragment {

    private static final String BASE_PATH_KEY = "server_base_path";

    /** Enforce max 1 running server at a time on mobile to prevent OOM. */
    private static final int MAX_CONCURRENT_SERVERS = 1;

    /** Minimum free memory (bytes) required before starting a server. */
    private static final long MIN_FREE_MEMORY_BYTES = 256L * 1024 * 1024; // 256 MB

    private String basePath;
    private ListView listView;
    private Button btnCreate;
    private List<ServerInstance> instances = new ArrayList<>();
    private ServerInstanceAdapter adapter;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public static ServerListFragment newInstance(String basePath) {
        ServerListFragment f = new ServerListFragment();
        Bundle args = new Bundle();
        args.putString(BASE_PATH_KEY, basePath);
        f.setArguments(args);
        return f;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) basePath = getArguments().getString(BASE_PATH_KEY);
        if (basePath == null) {
            File extDir = requireContext().getExternalFilesDir("servers");
            basePath = (extDir != null ? extDir : requireContext().getFilesDir()).getAbsolutePath()
                    + "/mojo-servers";
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);

        btnCreate = new Button(requireContext());
        btnCreate.setText("+ Create Server");
        btnCreate.setOnClickListener(v -> showCreateDialog());
        root.addView(btnCreate);

        listView = new ListView(requireContext());
        adapter = new ServerInstanceAdapter();
        listView.setAdapter(adapter);
        root.addView(listView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshList();
    }

    private void refreshList() {
        executor.submit(() -> {
            List<ServerInstance> loaded = ServerInstance.loadAll(basePath);
            uiHandler.post(() -> {
                instances.clear();
                instances.addAll(loaded);
                adapter.notifyDataSetChanged();
            });
        });
    }

    // ── Create flow ───────────────────────────────────────────────────────────────────────────

    private void showCreateDialog() {
        AlertDialog.Builder b = new AlertDialog.Builder(requireContext());
        b.setTitle("Create Server Instance");

        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 24);

        EditText etName = new EditText(requireContext());
        etName.setHint("Server name");
        layout.addView(etName);

        EditText etVersion = new EditText(requireContext());
        etVersion.setHint("Minecraft version (e.g. 1.21.4 or \"latest\")");
        layout.addView(etVersion);

        String[] types = {"Paper (recommended)", "Vanilla"};
        Spinner spinnerType = new Spinner(requireContext());
        spinnerType.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, types));
        layout.addView(spinnerType);

        EditText etXmx = new EditText(requireContext());
        etXmx.setHint("Max memory Xmx (default: 512M)");
        layout.addView(etXmx);

        TextView eulaNote = new TextView(requireContext());
        eulaNote.setText("By checking the box below you agree to the Minecraft EULA:\nhttps://aka.ms/MinecraftEULA");
        eulaNote.setTextSize(12f);
        layout.addView(eulaNote);

        CheckBox eulaCheck = new CheckBox(requireContext());
        eulaCheck.setText("I accept the Minecraft EULA");
        layout.addView(eulaCheck);

        b.setView(layout);
        b.setPositiveButton("Create", (dlg, which) -> {
            String name    = etName.getText().toString().trim();
            String version = etVersion.getText().toString().trim();
            String xmx     = etXmx.getText().toString().trim();
            if (xmx.isEmpty()) xmx = "512M";
            ServerType type = spinnerType.getSelectedItemPosition() == 0
                    ? ServerType.PAPER : ServerType.VANILLA;

            if (name.isEmpty() || version.isEmpty()) {
                Toast.makeText(requireContext(), "Name and version are required.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!eulaCheck.isChecked()) {
                Toast.makeText(requireContext(), "You must accept the Minecraft EULA.", Toast.LENGTH_LONG).show();
                return;
            }
            createInstance(name, version, type, xmx);
        });
        b.setNegativeButton("Cancel", null);
        b.show();
    }

    private void createInstance(String name, String version, ServerType type, String xmx) {
        ProgressDialog pd = new ProgressDialog(requireContext());
        pd.setTitle("Creating server\u2026");
        pd.setMessage("Downloading " + type.name().toLowerCase() + " " + version);
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setCancelable(false);
        pd.show();

        final String finalXmx = xmx;
        executor.submit(() -> {
            try {
                ServerInstance inst = new ServerInstance(name, basePath, type, version);
                inst.setXmx(finalXmx);
                inst.setAcceptedEula(true);
                inst.initDirectories();
                inst.writeEula();

                new ServerJarDownloader((downloaded, total) -> uiHandler.post(() -> {
                    if (total > 0) {
                        pd.setMax((int) total);
                        pd.setProgress((int) downloaded);
                    }
                })).download(inst);

                inst.saveMeta();
                uiHandler.post(() -> {
                    pd.dismiss();
                    Toast.makeText(requireContext(), "Server created: " + name, Toast.LENGTH_SHORT).show();
                    refreshList();
                });
            } catch (Exception e) {
                uiHandler.post(() -> {
                    pd.dismiss();
                    new AlertDialog.Builder(requireContext())
                            .setTitle("Error")
                            .setMessage("Failed to create server:\n" + e.getMessage())
                            .setPositiveButton("OK", null)
                            .show();
                });
            }
        });
    }

    // ── Start / Stop / Console ─────────────────────────────────────────────────────────────────

    private void startServer(ServerInstance inst) {
        // Enforce single concurrent server limit (stream API not available below API 24)
        int running = 0;
        for (net.kdt.pojavlaunch.lifecycle.ServerProcessManager m
                : ServerForegroundService.getAllManagers().values()) {
            if (m.isRunning()) running++;
        }
        if (running >= MAX_CONCURRENT_SERVERS) {
            Toast.makeText(requireContext(),
                    "A server is already running. Stop it first.", Toast.LENGTH_LONG).show();
            return;
        }

        // Memory safety check
        ActivityManager am = (ActivityManager) requireContext().getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        if (mi.availMem < MIN_FREE_MEMORY_BYTES) {
            new AlertDialog.Builder(requireContext())
                    .setTitle("Low Memory Warning")
                    .setMessage("Only " + (mi.availMem / 1024 / 1024) + " MB free. "
                            + "Starting a Minecraft server requires at least 512 MB. "
                            + "Close other apps and try again.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        Intent intent = new Intent(requireContext(), ServerForegroundService.class)
                .setAction(ServerForegroundService.ACTION_START)
                .putExtra(ServerForegroundService.EXTRA_INSTANCE_ID, inst.getId())
                .putExtra(ServerForegroundService.EXTRA_BASE_PATH, basePath);
        ContextCompat.startForegroundService(requireContext(), intent);

        Toast.makeText(requireContext(), "Starting " + inst.getName() + "\u2026", Toast.LENGTH_SHORT).show();
        adapter.notifyDataSetChanged();

        // Refresh list after a short delay so status reflects the new state
        uiHandler.postDelayed(this::refreshList, 1500);
    }

    private void stopServer(ServerInstance inst) {
        Intent intent = new Intent(requireContext(), ServerForegroundService.class)
                .setAction(ServerForegroundService.ACTION_STOP)
                .putExtra(ServerForegroundService.EXTRA_INSTANCE_ID, inst.getId());
        requireContext().startService(intent);
        Toast.makeText(requireContext(), "Stopping " + inst.getName() + "\u2026", Toast.LENGTH_SHORT).show();
        uiHandler.postDelayed(this::refreshList, 2000);
    }

    private void openConsole(ServerInstance inst) {
        if (!ServerForegroundService.isRunning(inst.getId())) {
            Toast.makeText(requireContext(), "Server is not running", Toast.LENGTH_SHORT).show();
            return;
        }
        requireActivity().getSupportFragmentManager()
                .beginTransaction()
                .replace(getId(), ServerConsoleFragment.newInstance(inst.getId(), inst.getName()), "server_console")
                .addToBackStack(null)
                .commit();
    }

    private void deleteInstance(ServerInstance inst) {
        if (ServerForegroundService.isRunning(inst.getId())) {
            Toast.makeText(requireContext(), "Stop the server before deleting.", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("Delete " + inst.getName() + "?")
                .setMessage("This will permanently delete all world data for this server.")
                .setPositiveButton("Delete", (d, w) -> executor.submit(() -> {
                    deleteDirectory(new File(inst.getPath()));
                    uiHandler.post(this::refreshList);
                }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void deleteDirectory(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] children = dir.listFiles();
        if (children != null) for (File child : children) deleteDirectory(child);
        dir.delete();
    }

    // ── Adapter ───────────────────────────────────────────────────────────────────────────────

    private class ServerInstanceAdapter extends BaseAdapter {
        @Override public int getCount() { return instances.size(); }
        @Override public ServerInstance getItem(int pos) { return instances.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int pos, View convertView, ViewGroup parent) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(16, 16, 16, 16);

            ServerInstance inst = instances.get(pos);
            boolean running = ServerForegroundService.isRunning(inst.getId());

            TextView title = new TextView(requireContext());
            title.setText(inst.getName() + " [" + inst.getServerType().name() + " " + inst.getMcVersion() + "]");
            title.setTextSize(16f);
            row.addView(title);

            TextView statusView = new TextView(requireContext());
            statusView.setText("Port: " + inst.getPort() + "  Status: " + (running ? "RUNNING" : "STOPPED"));
            row.addView(statusView);

            LinearLayout buttons = new LinearLayout(requireContext());
            buttons.setOrientation(LinearLayout.HORIZONTAL);

            if (!running) {
                Button btnStart = new Button(requireContext());
                btnStart.setText("Start");
                btnStart.setOnClickListener(v -> startServer(inst));
                buttons.addView(btnStart);

                Button btnDelete = new Button(requireContext());
                btnDelete.setText("Delete");
                btnDelete.setOnClickListener(v -> deleteInstance(inst));
                buttons.addView(btnDelete);
            } else {
                Button btnStop = new Button(requireContext());
                btnStop.setText("Stop");
                btnStop.setOnClickListener(v -> stopServer(inst));
                buttons.addView(btnStop);

                Button btnConsole = new Button(requireContext());
                btnConsole.setText("Console");
                btnConsole.setOnClickListener(v -> openConsole(inst));
                buttons.addView(btnConsole);
            }

            row.addView(buttons);
            return row;
        }
    }
}
