package com.ruwen.audioplayer.data.db

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ruwen.audioplayer.data.dao.AudioItemDao
import com.ruwen.audioplayer.data.dao.PlaylistDao
import com.ruwen.audioplayer.data.dao.PodcastDao
import com.ruwen.audioplayer.data.entity.AudioItem
import com.ruwen.audioplayer.data.entity.Episode
import com.ruwen.audioplayer.data.entity.Podcast
import com.ruwen.audioplayer.data.entity.Playlist

@Database(
    entities = [Playlist::class, AudioItem::class, Podcast::class, Episode::class],
    version = 11,
    // 导出 schema：迁移的正确性靠它与 MigrationTestHelper 校验。
    // 之前是 false，等于没有任何"结构真相"可对账，写迁移只能靠人眼比对。
    exportSchema = true
)
@TypeConverters(SubtitleStatusConverter::class, DownloadStatusConverter::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun playlistDao(): PlaylistDao
    abstract fun audioItemDao(): AudioItemDao
    abstract fun podcastDao(): PodcastDao

    companion object {
        private const val TAG = "AppDatabase"

        // 关于「为什么没有 MIGRATION_1_2 / MIGRATION_2_3」：
        // 本仓库在引入 Room 时就已经是 version = 4，原始代码里只有 MIGRATION_3_4 一条，
        // exportSchema = false（无 schemas/ 导出目录）、仓库内无 git 历史也无版本变更文档，
        // 即 v1/v2 的表结构在任何地方都不可考。
        // 此时凭空写 ALTER TABLE 属于猜测：列名猜错会在打开数据库时直接抛
        // SQLiteException，等于把「可能没数据可迁」变成「一定打不开 App」。
        // 因此保留 fallbackToDestructiveMigration() 作为兜底（清库重建，不崩溃），
        // 并用 onDestructiveMigration 把这次「静默丢数据」变成一条可观测的日志。
        // 若后续确认 v1/v2 真的发布过，再按真实 schema 补 Migration 并移除兜底。

        // v3 -> v4：新增 subtitleError 列，用于记录字幕生成失败的原因
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE audio_items ADD COLUMN subtitleError TEXT")
            }
        }

        // v4 -> v5：新增 subtitleEtaMillis 列，用于展示「生成中 · 预计还需 X」
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE audio_items ADD COLUMN subtitleEtaMillis INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        // v5 -> v6：新增播客模块两张表（podcasts / episodes）。
        // 只新增表、不动既有表，因此不会丢失本地播放列表数据。
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `podcasts` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`author` TEXT, " +
                        "`description` TEXT, " +
                        "`imageUrl` TEXT, " +
                        "`feedUrl` TEXT NOT NULL, " +
                        "`link` TEXT, " +
                        "`addedAt` INTEGER NOT NULL, " +
                        "`lastRefreshedAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_podcasts_feedUrl` " +
                        "ON `podcasts` (`feedUrl`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `episodes` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`podcastId` INTEGER NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`description` TEXT, " +
                        "`pubDate` INTEGER NOT NULL, " +
                        "`audioUrl` TEXT NOT NULL, " +
                        "`sizeBytes` INTEGER NOT NULL, " +
                        "`durationSec` INTEGER NOT NULL, " +
                        "`imageUrl` TEXT, " +
                        "`guid` TEXT NOT NULL, " +
                        "`downloadStatus` INTEGER NOT NULL, " +
                        "`localPath` TEXT, " +
                        "`downloadedAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`podcastId`) REFERENCES `podcasts`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_episodes_podcastId` " +
                        "ON `episodes` (`podcastId`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_episodes_podcastId_guid` " +
                        "ON `episodes` (`podcastId`, `guid`)"
                )
            }
        }

        // v6 -> v7：音频条目新增「展示标题」与「封面路径」两列。
        //
        // 两列都可空：旧行迁移后 displayTitle 为 null（界面回退显示 title）、
        // coverPath 为 null（显示占位图），用户重新添加一次音频即补上。
        // 注意：这里**不动 title 列**——它参与字幕共享身份（见 AudioItem 注释），
        // 改它会让已生成字幕的查找索引断掉。
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE audio_items ADD COLUMN displayTitle TEXT")
                db.execSQL("ALTER TABLE audio_items ADD COLUMN coverPath TEXT")
            }
        }

        // v7 -> v8：播放列表新增手动排序位列。
        // 默认 0：老数据迁移后全部为 0，列表按 `sortPosition ASC, id ASC` 展示，
        // 效果就是**创建顺序**（id 单调递增），与迁移前的可见顺序无关——用户要求默认创建顺序。
        // coverUri 列 v1 起就存在（一直闲置），本次开始复用它存列表封面路径，无需加列。
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE playlists ADD COLUMN sortPosition INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        // v8 -> v9：删掉 audio_items 的 subtitleProgress / subtitleEtaMillis 两列。
        //
        // 这两列存的是"字幕生成进度"，属于连续变化值，已改为放内存
        // （见 data/SubtitleProgressStore）：继续留在表里既没人写、也没人读，
        // 还会让后人误以为进度是权威数据。这里做一次彻底收口。
        //
        // 为什么是"建新表 + 搬数据 + 改名"而不是 `ALTER TABLE ... DROP COLUMN`：
        // DROP COLUMN 需要 SQLite 3.35+，而 minSdk 29（Android 10）自带的是 3.28，
        // 老设备上会直接报语法错误。这是 Room 官方文档推荐的迁移写法。
        // 注意：新表的列类型/可空性/外键/索引必须与实体完全一致，
        // 否则 Room 打开时会判定迁移不合法（并触发兜底的破坏性迁移）。
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `audio_items_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`playlistId` INTEGER NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`filePath` TEXT NOT NULL, " +
                        "`duration` INTEGER NOT NULL, " +
                        "`fileSize` INTEGER NOT NULL, " +
                        "`position` INTEGER NOT NULL, " +
                        "`displayTitle` TEXT, " +
                        "`coverPath` TEXT, " +
                        "`subtitlePath` TEXT, " +
                        "`subtitleStatus` INTEGER NOT NULL, " +
                        "`subtitleError` TEXT, " +
                        "`addedAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "INSERT INTO `audio_items_new` (" +
                        "`id`, `playlistId`, `title`, `filePath`, `duration`, `fileSize`, " +
                        "`position`, `displayTitle`, `coverPath`, `subtitlePath`, " +
                        "`subtitleStatus`, `subtitleError`, `addedAt`) " +
                        "SELECT `id`, `playlistId`, `title`, `filePath`, `duration`, `fileSize`, " +
                        "`position`, `displayTitle`, `coverPath`, `subtitlePath`, " +
                        "`subtitleStatus`, `subtitleError`, `addedAt` FROM `audio_items`"
                )
                db.execSQL("DROP TABLE `audio_items`")
                db.execSQL("ALTER TABLE `audio_items_new` RENAME TO `audio_items`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_audio_items_playlistId` " +
                        "ON `audio_items` (`playlistId`)"
                )
            }
        }

        // v9 -> v10：podcasts 增加「单集排序」两列（每个播客各记一套）。
        // 加列用 ALTER TABLE 即可（只有删列才需要重建表，见 MIGRATION_8_9）。
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `podcasts` ADD COLUMN `sortByTitle` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE `podcasts` ADD COLUMN `sortAscending` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        // v10 -> v11：audio_items 增加「封面远程来源」列，用于离线导入失败的封面事后补下载。
        // 加列用 ALTER TABLE 即可（只有删列才需要重建表，见 MIGRATION_8_9）。
        // 不回填历史数据：旧条目本来就没记录来源，刷新时会用 filePath↔episode.localPath
        // 反查一次并顺带填上（见 CoverBackfill）。
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `audio_items` ADD COLUMN `coverSourceUrl` TEXT")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ruwen_database"
                )
                    .addMigrations(
                        MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
                        MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11
                    )
                    .fallbackToDestructiveMigration()
                    .addCallback(DestroyLoggerCallback)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        /**
         * 兜底迁移被触发时打一条 W 级日志。
         * 之前这一步是完全静默的：用户播放列表被清空后无从追查，
         * 这也是「缺 MIGRATION_1_2/2_3」这条意见真正该被处理的地方。
         */
        private val DestroyLoggerCallback = object : RoomDatabase.Callback() {
            override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
                Log.w(
                    TAG,
                    "Room 触发破坏性迁移（旧库版本无可用 Migration），本地播放列表数据已重置"
                )
            }
        }
    }
}
