/*
 * Materialize - Materialize all those not material
 * Copyright (C) 2015  XiNGRZ <xxx@oxo.ooo>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.gedoor.mkiconapk;

import android.content.Intent;
import android.databinding.DataBindingUtil;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.ActivityOptionsCompat;
import android.support.v7.widget.PopupMenu;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.jakewharton.rxbinding.widget.RxTextView;
import com.gedoor.mkiconapk.app.ActivityOptionsCompatCompat;
import com.gedoor.mkiconapk.io.IconCacheManager;
import com.gedoor.mkiconapk.rx.RxPackageManager;
import com.gedoor.mkiconapk.util.LauncherUtil;
import com.gedoor.mkiconapk.util.UpdateUtil;
import com.trello.rxlifecycle.components.support.RxAppCompatActivity;
import com.umeng.analytics.MobclickAgent;

import java.io.FileNotFoundException;

import com.gedoor.mkiconapk.databinding.MainActivityBinding;
import rx.Observable;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;

public class MainActivity extends RxAppCompatActivity
        implements AppInfoAdapter.OnItemClickListener {

    private static final String TAG = "MainActivity";

    private static final int REQUEST_MAKE_ICON = 1;

    private IconCacheManager iconCacheManager;

    private AppInfoAdapter apps;

    private SearchPanelController searchPanelController;

    /**
     * @return Observable with apps known in Material Design filtered out
     */
    private static Observable.Transformer<AppInfo, AppInfo> filterGoodGuys() {
        return observable -> observable
                .filter(app -> !app.component.getPackageName().startsWith("org.cyanogenmod."));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        MainActivityBinding binding = DataBindingUtil.setContentView(this, R.layout.main_activity);

        setSupportActionBar(binding.toolbar);

        searchPanelController = new SearchPanelController(binding.searchBar);

        SearchMatcher searchMatcher = new SearchMatcher(() -> searchPanelController.getKeyword().getText().toString());

        binding.apps.setAdapter(apps = new AppInfoAdapter(this, Glide.with(this),
                searchMatcher, this));

        RxTextView.afterTextChangeEvents(searchPanelController.getKeyword())
                .compose(bindToLifecycle())
                .subscribe(avoid -> {
                    apps.data.applyFilter();
                    binding.apps.smoothScrollToPosition(0);
                });

        iconCacheManager = new IconCacheManager(this);

        RxPackageManager
                .intentActivities(getPackageManager(), Intents.MAIN, 0)
                .map(resolve -> AppInfo.from(resolve.activityInfo, getPackageManager(), iconCacheManager))
                .filter(app -> app != null)
                .compose(filterGoodGuys())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doOnCompleted(() -> binding.apps.smoothScrollToPosition(0))
                .subscribe(apps.data::addWithIndex);

        UpdateUtil.checkForUpdateAndPrompt(this);
    }

    @Override
    public void onItemClick(AppInfoAdapter.ViewHolder holder) {
        int index = holder.getLayoutPosition();
        AppInfo app = apps.data.get(index);

        Intent intent = new Intent(this, AdjustActivity.class);
        intent.putExtra("index", index);
        intent.putExtra("activity", app.activityInfo);

        startActivityForResult(intent,REQUEST_MAKE_ICON);
    }

    @Override
    public void onShowingPopupMenu(AppInfoAdapter.ViewHolder holder, PopupMenu menu) {
        AppInfo app = apps.data.get(holder.getLayoutPosition());
        if (app.cache == null) {
            onItemClick(holder);
        } else {
            menu.show();
        }
    }

    @Override
    public void onReinstallShortcutClick(AppInfoAdapter.ViewHolder holder) {
        AppInfo app = apps.data.get(holder.getLayoutPosition());

        Observable.just(app)
                .compose(bindToLifecycle())
                .filter(a -> a.cache != null)
                .observeOn(Schedulers.io())
                .map(a -> decodeIconFromCache(a.cache))
                .filter(icon -> icon != null)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(icon -> {
                    LauncherUtil.installShortcut(this, app.getIntent(), app.label, icon);
                    Toast.makeText(this, R.string.toast_added_to_home, Toast.LENGTH_SHORT).show();
                    MobclickAgent.onEvent(this, "install");
                });
    }

    @Nullable
    private Bitmap decodeIconFromCache(Uri uri) {
        try {
            return BitmapFactory.decodeStream(getContentResolver().openInputStream(uri));
        } catch (FileNotFoundException e) {
            Log.e(TAG, "Failed reading icon cache from " + uri.toString(), e);
            return null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_MAKE_ICON:
                if (data != null) {
                    invalidateIcon(data.getIntExtra("index", 0));
                }
                break;
            default:
                super.onActivityResult(requestCode, resultCode, data);
        }
    }

    private void invalidateIcon(int index) {
        AppInfo app = apps.data.get(index);
        if (app.resolveCache(iconCacheManager)) {
            app.cache = app.cache
                    .buildUpon()
                    .appendQueryParameter("t", String.valueOf(System.currentTimeMillis()))
                    .build();
        }

        apps.notifyItemChanged(index);
    }

    @Override
    protected void onResume() {
        super.onResume();
        MobclickAgent.onResume(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        MobclickAgent.onPause(this);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.about:
                startActivity(new Intent(this, AboutActivity.class));
                return true;
            case R.id.search:
                searchPanelController.open();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onBackPressed() {
        if (!searchPanelController.onBackPressed()) {
            super.onBackPressed();
        }
    }

}
