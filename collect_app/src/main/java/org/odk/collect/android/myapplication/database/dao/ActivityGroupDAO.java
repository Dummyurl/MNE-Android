package org.odk.collect.android.myapplication.database.dao;

import android.arch.persistence.room.Dao;

import org.odk.collect.android.myapplication.activitygroup.model.ActivityGroup;
import org.odk.collect.android.myapplication.database.base.BaseDAO;

@Dao
public abstract class ActivityGroupDAO implements BaseDAO<ActivityGroup> {
}
