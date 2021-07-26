/*
 * ManageCredentialsActivity.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar.auth;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.snackbar.Snackbar;

import net.cellar.BaseActivity;
import net.cellar.BuildConfig;
import net.cellar.R;
import net.cellar.model.Credential;
import net.cellar.supp.UiUtil;
import net.cellar.supp.Util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ManageCredentialsActivity extends AppCompatActivity {

    private final List<Credential> credentials = new ArrayList<>(4);

    private CoordinatorLayout coordinatorLayout;
    private RecyclerView recyclerViewCredentials;
    private CredentialsAdapter credentialsAdapter;
    private View viewNoCredentials;
    private AlertDialog dialogConfirmDeletion;

        /** {@inheritDoc} */
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        Window window = BaseActivity.setAnimations(this);
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        BaseActivity.setDarkMode(this);
        super.onCreate(savedInstanceState);

        getDelegate().setContentView(R.layout.activity_credentials);
        Toolbar toolbar = getDelegate().findViewById(R.id.toolbar);
        getDelegate().setSupportActionBar(toolbar);
        androidx.appcompat.app.ActionBar ab = getSupportActionBar();
        if (ab != null) {
            ab.setDisplayOptions(androidx.appcompat.app.ActionBar.DISPLAY_HOME_AS_UP | androidx.appcompat.app.ActionBar.DISPLAY_SHOW_TITLE);
        }

        this.coordinatorLayout = getDelegate().findViewById(R.id.coordinator_layout);
        this.viewNoCredentials = getDelegate().findViewById(R.id.textViewNoCredentials);
        this.recyclerViewCredentials = getDelegate().findViewById(R.id.recyclerViewCredentials);
        assert this.recyclerViewCredentials != null;
        this.recyclerViewCredentials.setHasFixedSize(true);
        this.credentialsAdapter = new CredentialsAdapter();
        this.recyclerViewCredentials.setAdapter(this.credentialsAdapter);
        this.recyclerViewCredentials.setLayoutManager(new LinearLayoutManager(this));
    }

    /** {@inheritDoc} */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int id = item.getItemId();
        if (id == android.R.id.home) {
            // if we hadn't this, the animations wouldn't work
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** {@inheritDoc} */
    @Override
    protected void onPause() {
        UiUtil.dismissDialog(this.dialogConfirmDeletion);
        super.onPause();
    }

    /** {@inheritDoc} */
    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        synchronized (this.credentials) {
            this.credentials.clear();
            this.credentials.addAll(AuthManager.getInstance().getCredentials());
            Collections.sort(this.credentials);
            this.viewNoCredentials.setVisibility(this.credentials.isEmpty() ? View.VISIBLE : View.GONE);
        }
        this.credentialsAdapter.notifyDataSetChanged();
    }

    private static class ViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener {

        private final TextView textViewRealm;
        private final TextView textViewUserid;
        private final ImageButton buttonDelete;

        /**
         * Constructor.
         * @param itemView View
         */
        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            this.textViewRealm = itemView.findViewById(R.id.textViewRealm);
            this.textViewUserid = itemView.findViewById(R.id.textViewUserid);
            this.buttonDelete = itemView.findViewById(R.id.buttonDelete);
            this.buttonDelete.setOnClickListener(this);
        }

        /** {@inheritDoc} */
        @Override
        public void onClick(View v) {
            Context ctx = v.getContext();
            if (!(ctx instanceof ManageCredentialsActivity)) return;
            final ManageCredentialsActivity activity = (ManageCredentialsActivity)ctx;
            int position = getAdapterPosition();
            if (position == RecyclerView.NO_POSITION) return;
            final Credential credential;
            synchronized (activity.credentials) {
                credential = activity.credentials.get(position);
            }
            if (credential == null) return;
            String realm = credential.getRealm();
            AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                    .setTitle(R.string.msg_confirmation)
                    .setIcon(R.drawable.ic_baseline_warning_amber_24)
                    .setMessage(activity.getString(R.string.msg_really_delete_credential, realm))
                    .setNegativeButton(R.string.label_no, (dialog, which) -> dialog.cancel())
                    .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                        dialog.dismiss();
                        boolean removed = AuthManager.getInstance().removeCredential(credential);
                        if (removed) {
                            Snackbar.make(activity.coordinatorLayout, activity.getString(R.string.msg_credential_deleted, realm), Snackbar.LENGTH_SHORT).show();
                        } else {
                            Snackbar.make(activity.coordinatorLayout, activity.getString(R.string.error_cant_delete, realm), Snackbar.LENGTH_SHORT).show();
                        }
                        activity.refresh();
                    })
                    ;
            activity.dialogConfirmDeletion = builder.create();
            Window dialogWindow = activity.dialogConfirmDeletion.getWindow();
            if (dialogWindow != null) dialogWindow.setBackgroundDrawableResource(R.drawable.background);
            activity.dialogConfirmDeletion.show();
        }
    }

    /**
     * The adapter for the RecyclerView.
     */
    class CredentialsAdapter extends RecyclerView.Adapter<ManageCredentialsActivity.ViewHolder> {

        private final Drawable dFtp, dHttp, dSftp;
        private final int iconSize = 64;

        /**
         * Constructor.
         */
        private CredentialsAdapter() {
            super();
            setHasStableIds(true);
            Bitmap bmHttp = Util.makeCharBitmap("HTTP", 0f, this.iconSize, this.iconSize, getResources().getColor(R.color.colorCredBasic), Color.TRANSPARENT, null);
            Bitmap bmFtp = Util.makeCharBitmap("FTP", 0f, this.iconSize, this.iconSize, getResources().getColor(R.color.colorCredFtp), Color.TRANSPARENT, null);
            Bitmap bmSftp = Util.makeCharBitmap("SFTP", 0f, this.iconSize, this.iconSize, getResources().getColor(R.color.colorCredSftp), Color.TRANSPARENT, null);
            this.dFtp = new BitmapDrawable(getResources(), bmFtp);
            this.dHttp = new BitmapDrawable(getResources(), bmHttp);
            this.dSftp = new BitmapDrawable(getResources(), bmSftp);
            this.dFtp.setBounds(0, 0, this.iconSize, this.iconSize);
            this.dHttp.setBounds(0, 0, this.iconSize, this.iconSize);
            this.dSftp.setBounds(0, 0, this.iconSize, this.iconSize);
        }

        /** {@inheritDoc} */
        @Override
        public int getItemCount() {
            int n;
            synchronized (ManageCredentialsActivity.this.credentials) {
                n = ManageCredentialsActivity.this.credentials.size();
            }
            return n;
        }

        /** {@inheritDoc} */
        @Override
        public long getItemId(int position) {
            long id;
            synchronized (ManageCredentialsActivity.this.credentials) {
                id = ManageCredentialsActivity.this.credentials.get(position).hashCode();
            }
            return id;
        }

        /** {@inheritDoc} */
        @SuppressLint("UseCompatLoadingForDrawables")
        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            Credential credential;
            synchronized (ManageCredentialsActivity.this.credentials) {
                credential = ManageCredentialsActivity.this.credentials.get(position);
            }
            holder.textViewRealm.setText(credential.getRealm());
            holder.textViewUserid.setText(credential.getUserid());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (BuildConfig.DEBUG) {
                    holder.itemView.setTooltipText(credential.getPassword() != null ? "pwd \"" + credential.getPassword() + "\"" : "pwd <null>");
                } else {
                    holder.textViewRealm.setTooltipText(getString(R.string.hint_credential_realm, credential.getRealm()));
                    holder.textViewUserid.setTooltipText(getString(R.string.hint_credential_userid, credential.getUserid()));
                }
            }
            switch (credential.getType()) {
                case Credential.TYPE_HTTP_BASIC:
                    holder.textViewRealm.setCompoundDrawables(this.dHttp, null, null, null);
                    break;
                case Credential.TYPE_FTP:
                    holder.textViewRealm.setCompoundDrawables(this.dFtp, null, null, null);
                    break;
                case Credential.TYPE_SFTP:
                    holder.textViewRealm.setCompoundDrawables(this.dSftp, null, null, null);
                    break;
                case Credential.TYPE_UNKNOWN:
                default:
                    holder.textViewRealm.setCompoundDrawables(null, null, null, null);
                    break;
            }
        }

        /** {@inheritDoc} */
        @NonNull
        @Override
        public ManageCredentialsActivity.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = getLayoutInflater().inflate(R.layout.credential_view, parent, false);
            return new ManageCredentialsActivity.ViewHolder(v);
        }

    }

}
