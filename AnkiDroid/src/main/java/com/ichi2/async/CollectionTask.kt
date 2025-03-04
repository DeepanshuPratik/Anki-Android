/****************************************************************************************
 * Copyright (c) 2009 Daniel Svärd <daniel.svard@gmail.com>                             *
 * Copyright (c) 2009 Edu Zamora <edu.zasu@gmail.com>                                   *
 * Copyright (c) 2011 Norbert Nagold <norbert.nagold@gmail.com>                         *
 *                                                                                      *
 * This program is free software; you can redistribute it and/or modify it under        *
 * the terms of the GNU General Public License as published by the Free Software        *
 * Foundation; either version 3 of the License, or (at your option) any later           *
 * version.                                                                             *
 *                                                                                      *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY      *
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A      *
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.             *
 *                                                                                      *
 * You should have received a copy of the GNU General Public License along with         *
 * this program.  If not, see <http://www.gnu.org/licenses/>.                           *
 ****************************************************************************************/

package com.ichi2.async

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.fasterxml.jackson.core.JsonToken
import com.ichi2.anki.*
import com.ichi2.anki.AnkiSerialization.factory
import com.ichi2.anki.exception.ConfirmModSchemaException
import com.ichi2.anki.exception.ImportExportException
import com.ichi2.libanki.*
import com.ichi2.libanki.Collection
import com.ichi2.libanki.Collection.CheckDatabaseResult
import com.ichi2.libanki.importer.AnkiPackageImporter
import com.ichi2.libanki.sched.DeckDueTreeNode
import com.ichi2.libanki.sched.TreeNode
import com.ichi2.utils.Computation
import com.ichi2.utils.KotlinCleanup
import org.apache.commons.compress.archivers.zip.ZipFile
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.*
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException

/**
 * This is essentially an AsyncTask with some more logging. It delegates to TaskDelegate the actual business logic.
 * It adds some extra check.
 * TODO: explain the goal of those extra checks. They seems redundant with AsyncTask specification.
 *
 * The CollectionTask should be created by the TaskManager. All creation of background tasks (except for Connection and Widget) should be done by sending a TaskDelegate to the ThreadManager.launchTask.
 *
 * @param <Progress> The type of progress that is sent by the TaskDelegate. E.g. a Card, a pairWithBoolean.
 * @param <Result>   The type of result that the TaskDelegate sends. E.g. a tree of decks, counts of a deck.
 */
@KotlinCleanup("IDE Lint")
@KotlinCleanup("Lots to do")
open class CollectionTask<Progress, Result>(val task: TaskDelegateBase<Progress, Result>, private val listener: TaskListener<in Progress, in Result>?, private var previousTask: CollectionTask<*, *>?) : BaseAsyncTask<Void, Progress, Result>(), Cancellable {
    /**
     * A reference to the application context to use to fetch the current Collection object.
     */
    protected var context: Context? = null
        private set

    /** Cancel the current task.
     * @return whether cancelling did occur.
     */
    @Suppress("deprecation") // #7108: AsyncTask
    override fun safeCancel(): Boolean {
        try {
            if (status != Status.FINISHED) {
                return cancel(true)
            }
        } catch (e: Exception) {
            // Potentially catching SecurityException, from
            // Thread.interrupt from FutureTask.cancel from
            // AsyncTask.cancel
            Timber.w(e, "Exception cancelling task")
        } finally {
            TaskManager.removeTask(this)
        }
        return false
    }

    private val col: Collection
        get() = CollectionHelper.instance.getCol(context)!!

    protected override fun doInBackground(vararg arg0: Void): Result? {
        return try {
            actualDoInBackground()
        } finally {
            TaskManager.removeTask(this)
        }
    }

    // This method and those that are called here are executed in a new thread
    @Suppress("deprecation") // #7108: AsyncTask
    protected fun actualDoInBackground(): Result? {
        super.doInBackground()
        // Wait for previous thread (if any) to finish before continuing
        if (previousTask != null && previousTask!!.status != Status.FINISHED) {
            Timber.d("Waiting for %s to finish before starting %s", previousTask!!.task, task.javaClass)
            try {
                previousTask!!.get()
                Timber.d("Finished waiting for %s to finish. Status= %s", previousTask!!.task, previousTask!!.status)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                // We have been interrupted, return immediately.
                Timber.d(e, "interrupted while waiting for previous task: %s", previousTask!!.task.javaClass)
                return null
            } catch (e: ExecutionException) {
                // Ignore failures in the previous task.
                Timber.e(e, "previously running task failed with exception: %s", previousTask!!.task.javaClass)
            } catch (e: CancellationException) {
                // Ignore cancellation of previous task
                Timber.d(e, "previously running task was cancelled: %s", previousTask!!.task.javaClass)
            }
        }
        TaskManager.setLatestInstance(this)
        context = AnkiDroidApp.instance.applicationContext

        // Skip the task if the collection cannot be opened
        if (task.requiresOpenCollection() && CollectionHelper.instance.getColSafe(context) == null) {
            Timber.e("CollectionTask CollectionTask %s as Collection could not be opened", task.javaClass)
            return null
        }
        // Actually execute the task now that we are at the front of the queue.
        return task.execTask(col, this)
    }

    /** Delegates to the [TaskListener] for this task.  */
    override fun onPreExecute() {
        super.onPreExecute()
        listener?.onPreExecute()
    }

    /** Delegates to the [TaskListener] for this task.  */
    override fun onProgressUpdate(vararg values: Progress) {
        super.onProgressUpdate(*values)
        listener?.onProgressUpdate(values[0])
    }

    /** Delegates to the [TaskListener] for this task.  */
    override fun onPostExecute(result: Result) {
        super.onPostExecute(result)
        listener?.onPostExecute(result)
        Timber.d("enabling garbage collection of mPreviousTask...")
        previousTask = null
    }

    override fun onCancelled() {
        TaskManager.removeTask(this)
        listener?.onCancelled()
    }

    class LoadDeckCounts : TaskDelegate<Void, List<TreeNode<DeckDueTreeNode>>?>() {
        override fun task(col: Collection, collectionTask: ProgressSenderAndCancelListener<Void>): List<TreeNode<DeckDueTreeNode>>? {
            Timber.d("doInBackgroundLoadDeckCounts")
            return try {
                // Get due tree
                col.sched.deckDueTree(collectionTask)
            } catch (e: RuntimeException) {
                Timber.e(e, "doInBackgroundLoadDeckCounts - error")
                null
            }
        }
    }

    abstract class DismissNotes<Progress>(protected val cardIds: List<Long>) : TaskDelegate<Progress, Computation<Array<Card>>>() {
        /**
         * @param col
         * @param collectionTask Represents the background tasks.
         * @return whether the task succeeded, and the array of cards affected.
         */
        @KotlinCleanup("fix requireNoNulls")
        override fun task(col: Collection, collectionTask: ProgressSenderAndCancelListener<Progress>): Computation<Array<Card>> {
            // query cards
            val cards = arrayOfNulls<Card>(cardIds.size)
            for (i in cardIds.indices) {
                cards[i] = col.getCard(cardIds[i])
            }
            try {
                col.db.database.beginTransaction()
                try {
                    val succeeded = actualTask(col, collectionTask, cards.requireNoNulls())
                    if (!succeeded) {
                        return Computation.err()
                    }
                    col.db.database.setTransactionSuccessful()
                } finally {
                    DB.safeEndInTransaction(col.db)
                }
            } catch (e: RuntimeException) {
                Timber.e(e, "doInBackgroundSuspendCard - RuntimeException on suspending card")
                CrashReportService.sendExceptionReport(e, "doInBackgroundSuspendCard")
                return Computation.err()
            }
            // pass cards back so more actions can be performed by the caller
            // (querying the cards again is unnecessarily expensive)
            return Computation.ok(cards.requireNoNulls())
        }

        /**
         * @param col The collection
         * @param collectionTask, where to send progress and listen for cancellation
         * @param cards Cards to which the task should be applied
         * @return Whether the tasks succeeded.
         */
        protected abstract fun actualTask(col: Collection, collectionTask: ProgressSenderAndCancelListener<Progress>, cards: Array<Card>): Boolean
    }

    class ChangeDeckMulti(cardIds: List<Long>, private val newDid: DeckId) : DismissNotes<Void?>(cardIds) {
        override fun actualTask(col: Collection, collectionTask: ProgressSenderAndCancelListener<Void?>, cards: Array<Card>): Boolean {
            Timber.i("Changing %d cards to deck: '%d'", cards.size, newDid)
            val deckData = col.decks.get(newDid)
            if (Decks.isDynamic(deckData)) {
                // #5932 - can't change to a dynamic deck. Use "Rebuild"
                Timber.w("Attempted to move to dynamic deck. Cancelling task.")
                return false
            }

            // Confirm that the deck exists (and is not the default)
            try {
                val actualId = deckData.getLong("id")
                if (actualId != newDid) {
                    Timber.w("Attempted to move to deck %d, but got %d", newDid, actualId)
                    return false
                }
            } catch (e: Exception) {
                Timber.e(e, "failed to check deck")
                return false
            }
            val changedCardIds = LongArray(cards.size)
            for (i in cards.indices) {
                changedCardIds[i] = cards[i].id
            }
            col.sched.remFromDyn(changedCardIds)
            val originalDids = LongArray(cards.size)
            for (i in cards.indices) {
                val card = cards[i]
                card.load()
                // save original did for undo
                originalDids[i] = card.did
                // then set the card ID to the new deck
                card.did = newDid
                val note = card.note()
                note.flush()
                // flush card too, in case, did has been changed
                card.flush()
            }
            val changeDeckMulti: UndoAction = UndoChangeDeckMulti(cards, originalDids)
            // mark undo for all at once
            col.markUndo(changeDeckMulti)
            return true
        }
    }

    class CheckDatabase : TaskDelegate<String, Pair<Boolean, CheckDatabaseResult?>>() {
        override fun task(col: Collection, collectionTask: ProgressSenderAndCancelListener<String>): Pair<Boolean, CheckDatabaseResult?> {
            Timber.d("doInBackgroundCheckDatabase")
            // Don't proceed if collection closed
            val result = col.fixIntegrity(TaskManager.ProgressCallback(collectionTask, AnkiDroidApp.appResources))
            return if (result.failed) {
                // we can fail due to a locked database, which requires knowledge of the failure.
                Pair(false, result)
            } else {
                // Close the collection and we restart the app to reload
                CollectionHelper.instance.closeCollection(true, "Check Database Completed")
                Pair(true, result)
            }
        }
    }

    @KotlinCleanup("Use StringBuilder to concatenate the strings")
    class ImportAdd(private val pathList: List<String>) : TaskDelegate<String, Triple<List<AnkiPackageImporter>?, Boolean, String?>>() {
        override fun task(col: Collection, collectionTask: ProgressSenderAndCancelListener<String>): Triple<List<AnkiPackageImporter>?, Boolean, String?> {
            Timber.d("doInBackgroundImportAdd")
            val res = AnkiDroidApp.instance.baseContext.resources

            var impList = arrayListOf<AnkiPackageImporter>()
            var errFlag = false
            var errList: String? = null

            for (path in pathList) {
                val imp = AnkiPackageImporter(col, path)
                imp.setProgressCallback(TaskManager.ProgressCallback(collectionTask, res))
                try {
                    imp.run()
                    impList.add(imp)
                } catch (e: ImportExportException) {
                    Timber.w(e)
                    errFlag = true
                    errList += File(path).name + "\n" + e.message + "\n"
                }
            }

            return Triple(if (impList.isEmpty()) null else impList, errFlag, errList)
        }
    }

    @KotlinCleanup("needs to handle null collection")
    class ImportReplace(private val pathList: List<String>) : TaskDelegate<String, Computation<*>>() {
        override fun task(col: Collection, collectionTask: ProgressSenderAndCancelListener<String>): Computation<*> {
            Timber.d("doInBackgroundImportReplace")
            val res = AnkiDroidApp.instance.baseContext.resources
            val context = col.context
            val colPath = CollectionHelper.getCollectionPath(context)
            // extract the deck from the zip file
            val dir = File(File(colPath).parentFile, "tmpzip")
            if (dir.exists()) {
                BackupManager.removeDir(dir)
            }
            // from anki2.py
            var colname = "collection.anki21"

            for (path in pathList) {
                val zip: ZipFile = try {
                    ZipFile(File(path))
                } catch (e: IOException) {
                    Timber.e(e, "doInBackgroundImportReplace - Error while unzipping")
                    CrashReportService.sendExceptionReport(e, "doInBackgroundImportReplace0")
                    return Computation.ERR
                }
                try {
                    // v2 scheduler?
                    if (zip.getEntry(colname) == null) {
                        colname = CollectionHelper.COLLECTION_FILENAME
                    }
                    Utils.unzipFiles(zip, dir.absolutePath, arrayOf(colname, "media"), null)
                } catch (e: IOException) {
                    CrashReportService.sendExceptionReport(e, "doInBackgroundImportReplace - unzip")
                    return Computation.ERR
                }
                val colFile = File(dir, colname).absolutePath
                if (!File(colFile).exists()) {
                    return Computation.ERR
                }
                var tmpCol: Collection? = null
                try {
                    tmpCol = Storage.collection(context, colFile)
                    if (!tmpCol.validCollection()) {
                        tmpCol.close()
                        return Computation.ERR
                    }
                } catch (e: Exception) {
                    Timber.e("Error opening new collection file... probably it's invalid")
                    try {
                        tmpCol!!.close()
                    } catch (e2: Exception) {
                        Timber.w(e2)
                        // do nothing
                    }
                    CrashReportService.sendExceptionReport(e, "doInBackgroundImportReplace - open col")
                    return Computation.ERR
                } finally {
                    tmpCol?.close()
                }
                collectionTask.doProgress(res.getString(R.string.importing_collection))
                try {
                    CollectionHelper.instance.getCol(context)
                    // unload collection
                    CollectionHelper.instance.closeCollection(true, "Importing new collection")
                    CollectionHelper.instance.lockCollection()
                } catch (e: Exception) {
                    Timber.w(e)
                }
                // overwrite collection
                val f = File(colFile)
                if (!f.renameTo(File(colPath))) {
                    // Exit early if this didn't work
                    return Computation.ERR
                }
                return try {
                    CollectionHelper.instance.unlockCollection()

                    // because users don't have a backup of media, it's safer to import new
                    // data and rely on them running a media db check to get rid of any
                    // unwanted media. in the future we might also want to duplicate this step
                    // import media
                    val nameToNum = HashMap<String, String>()
                    val numToName = HashMap<String, String>()
                    val mediaMapFile = File(dir.absolutePath, "media")
                    if (mediaMapFile.exists()) {
                        factory.createParser(mediaMapFile).use { jp ->
                            var name: String
                            var num: String
                            check(jp.nextToken() == JsonToken.START_OBJECT) { "Expected content to be an object" }
                            while (jp.nextToken() != JsonToken.END_OBJECT) {
                                num = jp.currentName()
                                name = jp.nextTextValue()
                                nameToNum[name] = num
                                numToName[num] = name
                            }
                        }
                    }
                    val mediaDir = Media.getCollectionMediaPath(colPath)
                    val total = nameToNum.size
                    var i = 0
                    for ((file, c) in nameToNum) {
                        val of = File(mediaDir, file)
                        if (!of.exists()) {
                            Utils.unzipFiles(zip, mediaDir, arrayOf(c), numToName)
                        }
                        ++i
                        collectionTask.doProgress(res.getString(R.string.import_media_count, (i + 1) * 100 / total))
                    }
                    zip.close()
                    // delete tmp dir
                    BackupManager.removeDir(dir)
                    Computation.OK
                } catch (e: RuntimeException) {
                    Timber.e(e, "doInBackgroundImportReplace - RuntimeException")
                    CrashReportService.sendExceptionReport(e, "doInBackgroundImportReplace1")
                    Computation.ERR
                } catch (e: FileNotFoundException) {
                    Timber.e(e, "doInBackgroundImportReplace - FileNotFoundException")
                    CrashReportService.sendExceptionReport(e, "doInBackgroundImportReplace2")
                    Computation.ERR
                } catch (e: IOException) {
                    Timber.e(e, "doInBackgroundImportReplace - IOException")
                    CrashReportService.sendExceptionReport(e, "doInBackgroundImportReplace3")
                    Computation.ERR
                }
            }
            return Computation.OK
        }
    }

    class ExportApkg(private val apkgPath: String, private val did: DeckId?, private val includeSched: Boolean, private val includeMedia: Boolean) : TaskDelegate<Void, Pair<Boolean, String?>>() {
        override fun task(col: Collection, collectionTask: ProgressSenderAndCancelListener<Void>): Pair<Boolean, String?> {
            Timber.d("doInBackgroundExportApkg")
            try {
                val exporter = if (did == null) {
                    AnkiPackageExporter(col, includeSched, includeMedia)
                } else {
                    AnkiPackageExporter(col, did, includeSched, includeMedia)
                }
                exporter.exportInto(apkgPath, col.context)
            } catch (e: FileNotFoundException) {
                Timber.e(e, "FileNotFoundException in doInBackgroundExportApkg")
                return Pair(false, null)
            } catch (e: IOException) {
                Timber.e(e, "IOException in doInBackgroundExportApkg")
                return Pair(false, null)
            } catch (e: JSONException) {
                Timber.e(e, "JSOnException in doInBackgroundExportApkg")
                return Pair(false, null)
            } catch (e: ImportExportException) {
                Timber.e(e, "ImportExportException in doInBackgroundExportApkg")
                return Pair(true, e.message)
            }
            return Pair(false, apkgPath)
        }
    }

    /**
     * Deletes the given field in the given model
     */
    class DeleteField(private val model: Model, private val field: JSONObject) : TaskDelegate<Void, Boolean>() {
        override fun task(col: Collection, collectionTask: ProgressSenderAndCancelListener<Void>): Boolean {
            Timber.d("doInBackGroundDeleteField")
            try {
                col.models.remField(model, field)
                col.save()
            } catch (e: ConfirmModSchemaException) {
                // Should never be reached
                e.log()
                return false
            }
            return true
        }
    }

    /**
     * Repositions the given field in the given model
     */
    class RepositionField(private val model: Model, private val field: JSONObject, private val index: Int) : TaskDelegate<Void, Boolean>() {
        override fun task(col: Collection, collectionTask: ProgressSenderAndCancelListener<Void>): Boolean {
            Timber.d("doInBackgroundRepositionField")
            try {
                col.models.moveField(model, field, index)
                col.save()
            } catch (e: ConfirmModSchemaException) {
                e.log()
                // Should never be reached
                return false
            }
            return true
        }
    }

    class FindEmptyCards : TaskDelegate<Int, List<Long>?>() {
        override fun task(col: Collection, collectionTask: ProgressSenderAndCancelListener<Int>): List<Long> {
            return col.emptyCids(collectionTask)
        }
    }

    class Reset : TaskDelegate<Void, Void?>() {
        override fun task(col: Collection, collectionTask: ProgressSenderAndCancelListener<Void>): Void? {
            col.sched.reset()
            return null
        }
    }

    companion object {
        @JvmStatic
        @VisibleForTesting
        fun nonTaskUndo(col: Collection): Card? {
            val sched = col.sched
            val card = col.undo()
            if (card == null) {
                /* multi-card action undone, no action to take here */
                Timber.d("Multi-select undo succeeded")
            } else {
                // cid is actually a card id.
                // a review was undone,
                /* card review undone, set up to review that card again */
                Timber.d("Single card review undo succeeded")
                card.startTimer()
                col.reset()
                sched.deferReset(card)
            }
            return card
        }
    }
}
