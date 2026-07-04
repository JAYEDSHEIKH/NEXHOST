package net.kdt.pojavlaunch.fragments;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import net.kdt.pojavlaunch.instances.ServerInstance;
import net.kdt.pojavlaunch.instances.ServerInstance.ServerType;
import net.kdt.pojavlaunch.downloader.ServerJarDownloader;
import net.kdt.pojavlaunch.lifecycle.ServerProcessManager;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;

/**
 * Fragment that lists all server instances and provides Create / Start / Stop / Console / Backup / Delete actions.
 *
 * Add to your navigation graph or activity:
 *   getSupportFragmentManager().beginTransaction()
 *       .replace(R.id.container, new ServerListFragment(), "server_list")
 *       .addToBackStack(null).commit();
 */
public class ServerListFragment extends Fragment {

    private static final String BASE_PATH_KEY = "server_base_path";

    private String basePath;
    private ListView listView;
    private Button btnCreate;
    private List<ServerInstance> instances = new ArrayList<>();
    private ServerInstanceAdapter adapter;
    private final Map<String, ServerProcessManager> managers = new ConcurrentHashMap<>();
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
        if (basePath == null) basePath = requireContext().getFilesDir() + "/mojo-servers";
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
        etVersion.setHint("Minecraft version (e.g. 1.21.4)");
        layout.addView(etVersion);

        String[] types = {"Paper (recommended)", "Vanilla"};
        int[] selectedType = {0};
        Spinner spinnerType = new Spinner(requireContext());
        spinnerType.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, types));
        layout.addView(spinnerType);

        EditText etXmx = new EditText(requireContext());
        etXmx.setHint("Max memory Xmx (default: 1024M)");
        layout.addView(etXmx);

        CheckBox eulaCheck = new CheckBox(requireContext());
        eulaCheck.setText("I accept the Minecraft EULA (https://aka.ms/MinecraftEULA)");
        layout.addView(eulaCheck);

        b.setView(layout);
        b.setPositiveButton("Create", (dlg, which) -> {
            String name = etName.getText().toString().trim();
            String version = etVersion.getText().toString().trim();
            String xmx = etXmx.getText().toString().trim();
            if (xmx.isEmpty()) xmx = "1024M";
            ServerType type = selectedType[0] == 0 ? ServerType.PAPER : ServerType.VANILLA;

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
        pd.setTitle("Creating server...");
        pd.setMessage("Downloading " + type.name() + " " + version);
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setCancelable(false);
        pd.show();

        executor.submit(() -> {
            try {
                ServerInstance inst = new ServerInstance(name, basePath, type, version);
                inst.setXmx(xmx);
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

    private void startServer(ServerInstance inst) {
        ServerProcessManager mgr = new ServerProcessManager(inst);
        mgr.addConsoleListener(new ServerProcessManager.ConsoleListener() {
            @Override public void onLine(String line) {}
            @Override public void onServerStarted() {
                uiHandler.post(() -> {
                    Toast.makeText(requireContext(), inst.getName() + " is ready!", Toast.LENGTH_SHORT).show();
                    adapter.notifyDataSetChanged();
                });
            }
            @Override public void onServerStopped(int code) {
                uiHandler.post(() -> adapter.notifyDataSetChanged());
            }
            @Override public void onCrash(int code, String tail) {
                uiHandler.post(() -> {
                    adapter.notifyDataSetChanged();
                    new AlertDialog.Builder(requireContext())
                            .setTitle("Server Crashed")
                            .setMessage(inst.getName() + " crashed (exit " + code + "):\n" + tail)
                            .setPositiveButton("OK", null).show();
                });
            }
        });
        managers.put(inst.getId(), mgr);
        executor.submit(() -> {
            try {
                mgr.start();
                uiHandler.post(() -> adapter.notifyDataSetChanged());
            } catch (Exception e) {
                uiHandler.post(() ->
                        Toast.makeText(requireContext(), "Start failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void stopServer(ServerInstance inst) {
        ServerProcessManager mgr = managers.get(inst.getId());
        if (mgr == null || !mgr.isRunning()) {
            Toast.makeText(requireContext(), "Not running", Toast.LENGTH_SHORT).show();
            return;
        }
        executor.submit(() -> {
            try { mgr.stopGraceful(); }
            catch (Exception e) { mgr.forceStop(); }
            uiHandler.post(() -> adapter.notifyDataSetChanged());
        });
    }

    private void openConsole(ServerInstance inst) {
        ServerProcessManager mgr = managers.get(inst.getId());
        if (mgr == null || !mgr.isRunning()) {
            Toast.makeText(requireContext(), "Server is not running", Toast.LENGTH_SHORT).show();
            return;
        }
        requireActivity().getSupportFragmentManager()
                .beginTransaction()
                .replace(getId(), ServerConsoleFragment.newInstance(inst.getId(), inst.getName()), "server_console")
                .addToBackStack(null)
                .commit();
    }

    private ServerProcessManager getManager(String id) { return managers.get(id); }

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
            ServerProcessManager mgr = managers.get(inst.getId());
            boolean running = mgr != null && mgr.isRunning();

            TextView title = new TextView(requireContext());
            title.setText(inst.getName() + " [" + inst.getServerType().name() + " " + inst.getMcVersion() + "]");
            title.setTextSize(16f);
            row.addView(title);

            TextView status = new TextView(requireContext());
            status.setText("Port: " + inst.getPort() + "  Status: " + (running ? "RUNNING" : inst.getStatus().name()));
            row.addView(status);

            LinearLayout buttons = new LinearLayout(requireContext());
            buttons.setOrientation(LinearLayout.HORIZONTAL);

            if (!running) {
                Button btnStart = new Button(requireContext());
                btnStart.setText("Start");
                btnStart.setOnClickListener(v -> startServer(inst));
                buttons.addView(btnStart);
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
