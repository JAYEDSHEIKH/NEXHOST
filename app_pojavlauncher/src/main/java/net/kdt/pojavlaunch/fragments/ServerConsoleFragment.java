package net.kdt.pojavlaunch.fragments;

import android.os.*;
import android.text.method.ScrollingMovementMethod;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import net.kdt.pojavlaunch.lifecycle.ServerProcessManager;

/**
 * Fragment that renders real-time server console output and an input field for commands.
 *
 * Obtain the manager from ServerListFragment (or a shared singleton registry) by server ID
 * and inject it via setManager() before the fragment becomes visible.
 */
public class ServerConsoleFragment extends Fragment implements ServerProcessManager.ConsoleListener {

    private static final String ARG_ID = "server_id";
    private static final String ARG_NAME = "server_name";
    private static final int MAX_LOG_CHARS = 50_000;

    private String serverId;
    private String serverName;
    private ServerProcessManager manager;

    private TextView tvConsole;
    private EditText etInput;
    private Button btnSend;
    private ScrollView scrollView;
    private CheckBox cbAutoScroll;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final StringBuilder logBuffer = new StringBuilder();

    public static ServerConsoleFragment newInstance(String serverId, String serverName) {
        ServerConsoleFragment f = new ServerConsoleFragment();
        Bundle args = new Bundle();
        args.putString(ARG_ID, serverId);
        args.putString(ARG_NAME, serverName);
        f.setArguments(args);
        return f;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            serverId = getArguments().getString(ARG_ID);
            serverName = getArguments().getString(ARG_NAME, "Server");
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16, 16, 16, 16);

        TextView title = new TextView(requireContext());
        title.setText("Console — " + serverName);
        title.setTextSize(18f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        cbAutoScroll = new CheckBox(requireContext());
        cbAutoScroll.setText("Auto-scroll");
        cbAutoScroll.setChecked(true);
        root.addView(cbAutoScroll);

        scrollView = new ScrollView(requireContext());
        tvConsole = new TextView(requireContext());
        tvConsole.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvConsole.setTextSize(11f);
        tvConsole.setMovementMethod(new ScrollingMovementMethod());
        tvConsole.setVerticalScrollBarEnabled(true);
        scrollView.addView(tvConsole);
        root.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout inputRow = new LinearLayout(requireContext());
        inputRow.setOrientation(LinearLayout.HORIZONTAL);

        etInput = new EditText(requireContext());
        etInput.setHint("Enter command...");
        inputRow.addView(etInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        btnSend = new Button(requireContext());
        btnSend.setText("Send");
        btnSend.setOnClickListener(v -> sendCommand());
        inputRow.addView(btnSend);

        root.addView(inputRow);

        Button btnStop = new Button(requireContext());
        btnStop.setText("Stop Server");
        btnStop.setOnClickListener(v -> stopServer());
        root.addView(btnStop);

        return root;
    }

    @Override
    public void onStart() {
        super.onStart();
        if (manager != null) manager.addConsoleListener(this);
    }

    @Override
    public void onStop() {
        super.onStop();
        if (manager != null) manager.removeConsoleListener(this);
    }

    public void setManager(ServerProcessManager manager) {
        this.manager = manager;
    }

    private void sendCommand() {
        if (manager == null || !manager.isRunning()) {
            appendLine("[Console] Server is not running.");
            return;
        }
        String cmd = etInput.getText().toString().trim();
        if (cmd.isEmpty()) return;
        try {
            manager.sendCommand(cmd);
            etInput.setText("");
        } catch (Exception e) {
            appendLine("[Console] Error: " + e.getMessage());
        }
    }

    private void stopServer() {
        if (manager == null) return;
        new Thread(() -> {
            try { manager.stopGraceful(); }
            catch (Exception e) { manager.forceStop(); }
        }).start();
    }

    private void appendLine(String line) {
        uiHandler.post(() -> {
            if (logBuffer.length() > MAX_LOG_CHARS) {
                logBuffer.delete(0, logBuffer.length() - MAX_LOG_CHARS / 2);
            }
            logBuffer.append(line).append("\n");
            tvConsole.setText(logBuffer.toString());
            if (cbAutoScroll != null && cbAutoScroll.isChecked()) {
                scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    @Override public void onLine(String line) { appendLine(line); }
    @Override public void onServerStarted() { appendLine("*** Server is ready! ***"); }
    @Override public void onServerStopped(int code) { appendLine("*** Server stopped (exit " + code + ") ***"); }
    @Override public void onCrash(int code, String tail) {
        appendLine("*** SERVER CRASHED (exit " + code + ") ***");
        appendLine(tail);
    }
}
