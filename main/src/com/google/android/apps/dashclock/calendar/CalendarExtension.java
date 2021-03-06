/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.apps.dashclock.calendar;

import com.google.android.apps.dashclock.LogUtils;
import com.google.android.apps.dashclock.api.DashClockExtension;
import com.google.android.apps.dashclock.api.ExtensionData;

import net.nurik.roman.dashclock.R;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import android.text.format.DateFormat;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;

import static com.google.android.apps.dashclock.LogUtils.LOGD;

/**
 * Calendar "upcoming appointment" extension.
 */
public class CalendarExtension extends DashClockExtension {
    private static final String TAG = LogUtils.makeLogTag(CalendarExtension.class);

    private static final long MINUTE_MILLIS = 60 * 1000;
    private static final long HOUR_MILLIS = 60 * MINUTE_MILLIS;
    private static final long MAX_CALENDAR_TIME_MILLIS = 6 * HOUR_MILLIS;

    @Override
    protected void onInitialize(boolean isReconnect) {
        super.onInitialize(isReconnect);
        if (!isReconnect) {
            addWatchContentUris(new String[]{
                    CalendarContract.Events.CONTENT_URI.toString()
            });
        }

        setUpdateWhenScreenOn(true);
    }

    @Override
    protected void onUpdateData(int reason) {
        Cursor cursor = openEventsCursor();

        long nextTimestamp = 0;
        long timeUntilNextAppointent = 0;
        while (cursor.moveToNext()) {
            nextTimestamp = cursor.getLong(EventsQuery.BEGIN);
            timeUntilNextAppointent = nextTimestamp - getCurrentTimestamp();
            if (timeUntilNextAppointent >= 0) {
                break;
            }

            // Skip over events that are not ALL_DAY but span multiple days, including
            // the next 6 hours. An example is an event that starts at 4pm yesterday
            // day and ends 6pm tomorrow.
        }

        if (cursor.isAfterLast()) {
            LOGD(TAG, "No upcoming appointments found.");
            cursor.close();
            publishUpdate(new ExtensionData());
            return;
        }

        int minutesUntilNextAppointment = (int) (timeUntilNextAppointent / MINUTE_MILLIS);

        String untilString;
        if (minutesUntilNextAppointment < 60) {
            untilString = getResources().getQuantityString(
                    R.plurals.calendar_template_mins,
                    minutesUntilNextAppointment,
                    minutesUntilNextAppointment);
        } else {
            int hours = Math.round(minutesUntilNextAppointment / 60f);
            untilString = getResources().getQuantityString(
                    R.plurals.calendar_template_hours, hours, hours);
        }
        String eventTitle = cursor.getString(EventsQuery.TITLE);
        long eventId = cursor.getLong(EventsQuery.EVENT_ID);
        long eventBegin = cursor.getLong(EventsQuery.BEGIN);
        long eventEnd = cursor.getLong(EventsQuery.END);
        cursor.close();

        Calendar nextEventCalendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        nextEventCalendar.setTimeInMillis(nextTimestamp);

        String expandedBody;
        if (DateFormat.is24HourFormat(this)) {
            expandedBody = new SimpleDateFormat("kk:mm").format(nextEventCalendar.getTime());
        } else {
            expandedBody = new SimpleDateFormat("h:mm a").format(nextEventCalendar.getTime());
        }

        publishUpdate(new ExtensionData()
                .visible(timeUntilNextAppointent >= 0
                        && timeUntilNextAppointent <= MAX_CALENDAR_TIME_MILLIS)
                .icon(R.drawable.ic_extension_calendar)
                .status(untilString)
                .expandedTitle(eventTitle)
                .expandedBody(expandedBody)
                .clickIntent(new Intent(Intent.ACTION_VIEW)
                        .setData(Uri.withAppendedPath(CalendarContract.Events.CONTENT_URI,
                                Long.toString(eventId)))
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, eventBegin)
                        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, eventEnd)));
    }

    private static long getCurrentTimestamp() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).getTimeInMillis();
    }

    private Cursor openEventsCursor() {
        long now = getCurrentTimestamp();
        return getContentResolver().query(
                CalendarContract.Instances.CONTENT_URI.buildUpon()
                        .appendPath(Long.toString(now))
                        .appendPath(Long.toString(now + MAX_CALENDAR_TIME_MILLIS))
                        .build(),
                EventsQuery.PROJECTION,
                CalendarContract.Instances.ALL_DAY + "=0 AND "
                        + CalendarContract.Instances.SELF_ATTENDEE_STATUS + "!="
                        + CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED + " AND "
                        + CalendarContract.Instances.STATUS + "!="
                        + CalendarContract.Instances.STATUS_CANCELED + " AND "
                        + CalendarContract.Instances.VISIBLE + "!=0",
                null,
                CalendarContract.Instances.BEGIN);
    }

    private interface EventsQuery {
        String[] PROJECTION = {
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.TITLE,
        };

        int EVENT_ID = 0;
        int BEGIN = 1;
        int END = 2;
        int TITLE = 3;
    }
}
