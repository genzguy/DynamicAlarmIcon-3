/*
 * Copyright (C) 2014 GermainZ@xda-developers.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cz.babi.android.xposed.dynamicalarmicon2;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.AlarmManager;
import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.Pair;
import android.view.View;
import android.widget.*;
import com.germainz.dynamicalarmicon.ClockDrawable;
import com.germainz.dynamicalarmicon.Config;
import com.germainz.dynamicalarmicon.TouchWizClockDrawable;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static de.robv.android.xposed.XposedHelpers.*;

/**
 * @author GermainZ
 * @author baaabovka
 */
public class XposedMod implements IXposedHookLoadPackage {
    private Context mContext;
    private ClockDrawable mClockDrawable;
    private ClockDrawable mClockDrawableStatusbar;
    private ContentObserver mNextAlarmObserver;
    private BroadcastReceiver mNextAlarmChangedReceiver;
    private AlarmManager mAlarmManager;
    private Config mConfig = new Config();
    private static final Set<String> CLOCK_PACKAGES = new HashSet<>(Arrays.asList(new String[]{
    "com.android.deskclock", "com.google.android.deskclock", "com.mobitobi.android.gentlealarmtrial",
    "com.mobitobi.android.gentlealarm"
    }));
    private static final Pattern TIME_PATTERN = Pattern.compile("([01]?[0-9]|2[0-3]):([0-5][0-9])");
    private static final String START_UP_INTENT = "com.germainz.dynamicalarmicon.START_UP";
    private static final int CLOCK_STYLE_AOSP = 0;
    private static final int CLOCK_STYLE_TOUCHWIZ = 1;

    private static final int StatusbarNotificationIdx = Config.IS_LOLLIPOP_OR_ABOVE ? 0 : 1;
    private static final int StatusBarIconViewIdx = Config.IS_LOLLIPOP_OR_ABOVE ? 1 : 2;

    private int statusbarIconHeight, statusbarHeaderIconSize;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals("com.android.systemui")) {
            hookSystemUI(lpparam);
        } else if ((Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) && (lpparam.packageName.equals("ch.bitspin.timely"))) {
            // For Lollipop Timely uses the official AlarmManager API to show the statusbar icon
            // On older versions it uses a hidden API which requires a hook directly into Timely
            hookTimely(lpparam);
        }
    }

    private void hookSystemUI(final XC_LoadPackage.LoadPackageParam lpparam) {
        final ClassLoader classLoader = lpparam.classLoader;

        statusbarIconHeight = Math.round(20 * Resources.getSystem().getDisplayMetrics().density);
        statusbarHeaderIconSize = Math.round(18 * Resources.getSystem().getDisplayMetrics().density);

        Object statusBarNotificationClass;
        if(Config.IS_JELLYBEANMR2_OR_ABOVE) {
            statusBarNotificationClass = StatusBarNotification.class;
        } else {
            statusBarNotificationClass = "com.android.internal.statusbar.StatusBarNotification";
        }

        XC_MethodHook notificationDataEntryHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Object notificationObject = param.args[StatusbarNotificationIdx];
                final String packageName = (String) getObjectField(notificationObject, "pkg");
                if(!CLOCK_PACKAGES.contains(packageName))
                    return;

                Notification notification = (Notification) getObjectField(notificationObject, "notification");
                RemoteViews contentView = notification.contentView;

                List<CharSequence> notificationText = new ArrayList<CharSequence>();
                ArrayList<Parcelable> actions = (ArrayList<Parcelable>) getObjectField(contentView, "mActions");
                for (Parcelable parcelable : actions) {
                    Parcel parcel = Parcel.obtain();
                    parcelable.writeToParcel(parcel, 0);
                    parcel.setDataPosition(0);
                    /* RemoteViews.setTextViewText(…) adds a ReflectionAction action:
                     *   ReflectionAction(int viewId, String methodName, CharSequence value)
                     * ReflectionAction writes, in order, the following values to the parcelable:
                     *   int TAG: 2 for ReflectionAction.
                     *   int viewId: the view's ID, we don't need that.
                     *   String methodName: "setText".
                     *   int type: CHAR_SEQUENCE = 10, but we don't need to check it since it's always 10
                     *             with setText.
                     *   CharSequence value: the text we want, written using TextUtils.writeToParcel(…)
                     */

                    // Check if it's a ReflectionAction.
                    if (parcel.readInt() != 2) {
                        continue;
                    }

                    parcel.readInt(); // discard the viewId.
                    // Check if methodName = "setText"
                    if (parcel.readString().equals("setText")) {
                        parcel.readInt(); // discard type.
                        // Get value.
                        notificationText.add(TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(parcel));
                    }
                    parcel.recycle();
                }

                Pair<Integer, Integer> alarmTime = null;
                for (CharSequence txt : notificationText) {
                    // The time should be in the notification's text, not title.
                    alarmTime = getTimeFromString(String.valueOf(txt));
                    if (alarmTime != null) {
                        break;
                    }
                }

                if (alarmTime == null) return;

                // Set the small icon.
                ImageView icon = (ImageView) param.args[StatusBarIconViewIdx];
                icon.setImageDrawable(getClockDrawable(alarmTime.first, alarmTime.second));

                // Set the large icon (shown in the notification shade) for the normal views.
                // The expanded view's large icon is set, if needed, in setBigContentView's hook.
                int width = (int) icon.getResources().getDimension(
                        android.R.dimen.notification_large_icon_width);
                int height = (int) icon.getResources().getDimension(
                        android.R.dimen.notification_large_icon_height);

                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                ClockDrawable clockDrawable = getClockDrawable(alarmTime.first, alarmTime.second);
                clockDrawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
                clockDrawable.draw(canvas);
                contentView.setImageViewBitmap(android.R.id.icon, bitmap);

                /* Workaround for expanded view. */
                if(Config.IS_JELLYBEAN_OR_ABOVE) {
                    @SuppressLint("NewApi") RemoteViews bigContentView = notification.bigContentView;
                    bigContentView.setImageViewBitmap(android.R.id.icon, bitmap);
                }

                setAdditionalInstanceField(param.thisObject, "hourLocal", alarmTime.first);
                setAdditionalInstanceField(param.thisObject, "minuteLocal", alarmTime.second);

                /* Workaround for Notification icon shown in the notification shade.  */
                if(Config.IS_MARSHMALLOW_OR_ABOVE) {
                    Object statusBarIconView = param.args[1];

                    setAdditionalInstanceField(statusBarIconView, "hourLocal", alarmTime.first);
                    setAdditionalInstanceField(statusBarIconView, "minuteLocal", alarmTime.second);

                    findAndHookMethod("com.android.systemui.statusbar.StatusBarIconView", classLoader, "getIcon",
                            "com.android.internal.statusbar.StatusBarIcon", new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                    String pkg = (String) getObjectField(param.args[0], "pkg");
                                    if(CLOCK_PACKAGES.contains(pkg)) {
                                        Object hour = getAdditionalInstanceField(param.thisObject, "hourLocal");
                                        Object minute = getAdditionalInstanceField(param.thisObject, "minuteLocal");
                                        if(hour!=null && minute!=null)
                                            param.setResult(getClockDrawable((int) hour, (int) minute));
                                    }
                                }
                            });

                }
            }
        };

        if(Config.IS_MARSHMALLOW_OR_ABOVE) {
            findAndHookConstructor("com.android.systemui.statusbar.NotificationData.Entry", classLoader,
                    statusBarNotificationClass, "com.android.systemui.statusbar.StatusBarIconView", notificationDataEntryHook);
        } else if(Config.IS_LOLLIPOP_OR_ABOVE) {
            findAndHookConstructor("com.android.systemui.statusbar.NotificationData.Entry", classLoader,
                    statusBarNotificationClass, "com.android.systemui.statusbar.StatusBarIconView", notificationDataEntryHook);
        } else {
            findAndHookConstructor("com.android.systemui.statusbar.NotificationData.Entry", classLoader,
                    IBinder.class, statusBarNotificationClass, "com.android.systemui.statusbar.StatusBarIconView", notificationDataEntryHook);
        }

        if (Config.IS_JELLYBEAN_OR_ABOVE) {
            XC_MethodHook notificationIconViewHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    View bigContentView = (View) param.args[0];
                    if(bigContentView!=null) {
                        ImageView icon = (ImageView) bigContentView.findViewById(android.R.id.icon);
                        Integer hour = (Integer) getAdditionalInstanceField(param.thisObject, "hourLocal");
                        if(hour!=null) {
                            Integer minute = (Integer) getAdditionalInstanceField(param.thisObject, "minuteLocal");
                            icon.setImageDrawable(getClockDrawable(hour, minute));
                        }
                    }
                }
            };

            if(Config.IS_MARSHMALLOW_OR_ABOVE) {
                /* For Marshmallow it is done directly within the constructor hook of NotificationData.Entry. */
            } else if(Config.IS_KITKAT_OR_ABOVE) {
                findAndHookMethod("com.android.systemui.statusbar.NotificationData.Entry", classLoader,
                        "setBigContentView", View.class, notificationIconViewHook);
            } else {
                findAndHookMethod("com.android.systemui.statusbar.NotificationData.Entry", classLoader,
                        "setLargeView", View.class, notificationIconViewHook);
            }
        }

        findAndHookMethod("com.android.systemui.statusbar.phone.PhoneStatusBar", classLoader, "makeStatusBarView",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                        mContext = (Context) getObjectField(param.thisObject, "mContext");
                        /* Beginning with Android Lollipop NEXT_ALARM_FORMATTED has been depreciated
                         * instead we need to register a broadcast receiver to receive an intent
                         * with action ACTION_NEXT_ALARM_CLOCK_CHANGED
                         */
                        if (Config.IS_LOLLIPOP_OR_ABOVE) {
                            mNextAlarmChangedReceiver = new BroadcastReceiver() {
                                @Override
                                public void onReceive(Context context, Intent intent) {
                                    updateAlarmIcon(param.thisObject);
                                }
                            };
                            mContext.registerReceiver(mNextAlarmChangedReceiver, new IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED));
                        } else {
                            Uri nextAlarmUri = Settings.System.getUriFor(Settings.System.NEXT_ALARM_FORMATTED);
                            mNextAlarmObserver = new ContentObserver(new Handler()) {
                                @Override
                                public void onChange(boolean selfChange) {
                                    updateAlarmIcon(param.thisObject);
                                }
                            };
                            mContext.getContentResolver().registerContentObserver(nextAlarmUri, false, mNextAlarmObserver);
                        }

                        // Only needed on start up.
                        BroadcastReceiver startUpReceiver = new BroadcastReceiver() {
                            @Override
                            public void onReceive(Context context, Intent intent) {
                                updateAlarmIcon(param.thisObject);
                                mContext.unregisterReceiver(this);
                            }
                        };
                        mContext.registerReceiver(startUpReceiver, new IntentFilter(START_UP_INTENT));
                    }
                }
        );

        if (Config.IS_KITKAT_OR_ABOVE) {
            findAndHookMethod("com.android.systemui.statusbar.phone.PhoneStatusBar", classLoader, "destroy",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                            if (Config.IS_LOLLIPOP_OR_ABOVE) {
                                mContext.unregisterReceiver(mNextAlarmChangedReceiver);
                            } else {
                                mContext.getContentResolver().unregisterContentObserver(mNextAlarmObserver);
                            }
                        }
                    }
            );
        }

        XposedBridge.hookAllConstructors(findClass("com.android.systemui.statusbar.phone.PhoneStatusBarPolicy", classLoader),
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        // For when the device first starts up.
                        ((Context) param.args[0]).sendBroadcast(new Intent(START_UP_INTENT));
                    }
                }
        );

        if(Config.IS_JELLYBEANMR2_OR_ABOVE) {
            findAndHookMethod("com.android.systemui.statusbar.StatusBarIconView", classLoader, "updateDrawable",
                    boolean.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            String slot = (String)getObjectField(param.thisObject, "mSlot");
                            if(slot!=null && slot.equals("alarm_clock")) {
                                param.setResult(true);
                            }
                        }
                    }
            );
        }

        if (Config.IS_LOLLIPOP_OR_ABOVE) {
            /* Set the alarm clock drawable in the expanded status bar */
            findAndHookMethod("com.android.systemui.statusbar.phone.StatusBarHeaderView", classLoader, "onNextAlarmChanged",
                    AlarmManager.AlarmClockInfo.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (param.args[0] != null) {
                                if (mClockDrawableStatusbar == null) {
                                    mClockDrawableStatusbar = getClockDrawable(0, 0);
                                }

                                TextView mAlarmStatus = (TextView) getObjectField(param.thisObject, "mAlarmStatus");
                                mClockDrawableStatusbar.setColorFilter(mAlarmStatus.getCurrentTextColor(), PorterDuff.Mode.MULTIPLY);
                                mClockDrawableStatusbar.setBounds(0, 0, statusbarHeaderIconSize, statusbarHeaderIconSize);
                                mAlarmStatus.setCompoundDrawables(mClockDrawableStatusbar, null, null, null);
                            }
                        }
                    }
            );
        }
    }

    private void hookTimely(final XC_LoadPackage.LoadPackageParam lpparam) {
        final ClassLoader classLoader = lpparam.classLoader;

        findAndHookMethod("ch.bitspin.timely.alarm.AlarmManager", classLoader, "f", "ch.bitspin.timely.data.AlarmClock",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        // Timely actually does this for Android 4.1 and less, but not for 4.2 and higher.
                        String nextAlarmFormatted = "";
                        Context context = (Context) getObjectField(param.thisObject, "e");
                        Object alarmClock = param.args[0];
                        if (alarmClock != null) {
                            Object nextAlarmUTC = callStaticMethod(findClass("ch.bitspin.timely.alarm.e", classLoader),
                                    "a", alarmClock);
                            if (nextAlarmUTC != null) {
                                Object nextAlarmLocal = callMethod(nextAlarmUTC, "c", (Object) null);
                                String timeFormat = "E kk:mm";
                                if (!DateFormat.is24HourFormat(context))
                                    timeFormat = "E h:mm aa";
                                nextAlarmFormatted = (String) DateFormat.format(timeFormat,
                                        (Long) callMethod(nextAlarmLocal, "d"));
                            }
                        }
                        Settings.System.putString(context.getContentResolver(),
                                Settings.System.NEXT_ALARM_FORMATTED, nextAlarmFormatted);
                    }
                }
        );
    }

    private Pair<Integer, Integer> getTimeFromString(String s) {
        Matcher matcher = TIME_PATTERN.matcher(s);
        if (matcher.find()) {
            String[] nextAlarmTime = TextUtils.split(matcher.group(), ":");
            int nextAlarmHour = Integer.parseInt(nextAlarmTime[0]);
            int nextAlarmMinute = Integer.parseInt(nextAlarmTime[1]);
            return new Pair<>(nextAlarmHour, nextAlarmMinute);
        }
        return null;
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void updateAlarmIcon(Object thisObject) {
        int hour, minute;
        if (Config.IS_LOLLIPOP_OR_ABOVE) {
            if (mAlarmManager == null) {
                mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
            }
            AlarmManager.AlarmClockInfo mNextAlarm = mAlarmManager.getNextAlarmClock();
            if (mNextAlarm == null) return;

            Calendar calendar = GregorianCalendar.getInstance();
            calendar.setTime(new Date(mNextAlarm.getTriggerTime()));
            hour = calendar.get(Calendar.HOUR);
            minute = calendar.get(Calendar.MINUTE);
        } else {
            String nextAlarm = Settings.System.getString(mContext.getContentResolver(), Settings.System.NEXT_ALARM_FORMATTED);
            if (nextAlarm.isEmpty()) {
            /* Some vendors (e.g. HTC) seem to remove the alarm_clock status bar icon
             * instead of toggling its visibility, so we'll need to look for it again in
             * updateAlarmIcon next time an alarm is set.
             */
                mClockDrawable = null;
                return;
            }

            Pair<Integer, Integer> nextAlarmTime = getTimeFromString(nextAlarm);
            if (nextAlarmTime == null) return;

            hour = nextAlarmTime.first;
            minute = nextAlarmTime.second;
        }

        if(mClockDrawable==null) {
            /* https://github.com/android/platform_frameworks_base/commit/66ac133971f4e2f80cd7cfff89cc6f8a3f7e899f#diff-861aa855219bd25761dc6f44dfb97937 */
            LinearLayout statusIcons;
            if(Config.IS_MARSHMALLOW_OR_ABOVE) {
                Object iconController = getObjectField(thisObject, "mIconController");
                statusIcons = (LinearLayout)getObjectField(iconController, "mStatusIcons");
            } else {
                statusIcons = (LinearLayout) getObjectField(thisObject, "mStatusIcons");
            }

            for(int i = 0; i<statusIcons.getChildCount(); i++) {
                ImageView alarm_clock = (ImageView) statusIcons.getChildAt(i);
                if(getObjectField(alarm_clock, "mSlot").equals("alarm_clock")) {
                    mClockDrawable = getClockDrawable(hour, minute);
                    alarm_clock.setImageDrawable(mClockDrawable);

                    if(Config.IS_LOLLIPOP_OR_ABOVE) {
                        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            Object iconController = getObjectField(thisObject, "mIconController");
                            alarm_clock.getLayoutParams().width = getIntField(iconController, "mIconSize")+2*getIntField(iconController, "mIconHPadding");
                        } else {
                            alarm_clock.getLayoutParams().width = getIntField(thisObject, "mIconSize")+2*getIntField(thisObject, "mIconHPadding");
                        }
                        alarm_clock.getLayoutParams().height = statusbarIconHeight;
                    }
                }
            }
        } else {
            mClockDrawable.setTime(hour, minute);
        }

        if (Config.IS_LOLLIPOP_OR_ABOVE) {
            if (mClockDrawableStatusbar == null) {
                mClockDrawableStatusbar = getClockDrawable(hour, minute);
            } else {
                mClockDrawableStatusbar.setTime(hour, minute);
            }
        }
    }

    private ClockDrawable getClockDrawable(int hour, int minute) {
        int style = mConfig.getClockStyle();
        int color = mConfig.getClockColor();
        if (style == CLOCK_STYLE_AOSP)
            return new ClockDrawable(color, hour, minute);
        else
            return new TouchWizClockDrawable(color, hour, minute);
    }
}
