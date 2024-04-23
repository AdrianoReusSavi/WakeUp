package com.ars.wakeup.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface WakeUpDao {
    @Query("SELECT * FROM history")
    fun getAll(): List<WakeUpHistory>

    @Insert
    fun insertAll(vararg history: WakeUpHistory)
}