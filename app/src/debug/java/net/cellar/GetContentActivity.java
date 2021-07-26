/*
 * GetContentActivity.java
 * Copyright (c) livodeu 2021.
 * This source code is subject to the license to be found in the file LICENSE.
 */

package net.cellar;

import android.content.Intent;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Issues an {@link Intent#ACTION_GET_CONTENT} intent and passes its result to {@link MainActivity}.
 * Exists in debug versions only.
 */
public class GetContentActivity extends AppCompatActivity {

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent i) {
        super.onActivityResult(requestCode, resultCode, i);
        if (resultCode == RESULT_CANCELED || i == null || i.getData() == null) {
            finish();
            return;
        }
        Intent main = new Intent(getApplicationContext(), MainActivity.class);
        main.setAction(Intent.ACTION_VIEW);
        main.setData(i.getData());
        main.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(main);
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        pick.setType("*/*");
        startActivityForResult(pick, 101);
    }
}
