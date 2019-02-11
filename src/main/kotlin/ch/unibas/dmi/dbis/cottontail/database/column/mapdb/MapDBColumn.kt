package ch.unibas.dmi.dbis.cottontail.database.column.mapdb

import ch.unibas.dmi.dbis.cottontail.database.column.*
import ch.unibas.dmi.dbis.cottontail.database.general.DBO
import ch.unibas.dmi.dbis.cottontail.database.general.Transaction
import ch.unibas.dmi.dbis.cottontail.database.general.TransactionStatus
import ch.unibas.dmi.dbis.cottontail.database.entity.Entity
import ch.unibas.dmi.dbis.cottontail.model.basics.Tuple
import ch.unibas.dmi.dbis.cottontail.model.exceptions.DatabaseException
import ch.unibas.dmi.dbis.cottontail.model.exceptions.TransactionException
import kotlinx.coroutines.*
import org.mapdb.*
import org.mapdb.volume.MappedFileVol

import java.nio.file.Path
import java.util.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.collections.ArrayList
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Represents a single column in the Cottontail DB model. A [MapDBColumn] record is identified by a tuple ID (long)
 * and can hold an arbitrary value. Usually, multiple [MapDBColumn]s make up an [Entity].
 *
 * @see Entity
 *
 * @param <T> Type of the value held by this [MapDBColumn].
 *
 * @author Ralph Gasser
 * @version 1.0
 */
internal class MapDBColumn<T: Any>(override val name: String, entity: Entity): Column<T> {
    /** The [Path] to the [Entity]'s main folder. */
    override val path: Path = entity.path.resolve("col_$name.db")

    /** The fully qualified name of this [MapDBColumn] */
    override val fqn: String = "${entity.parent!!.name}.${entity.name}.$name"

    /** The parent [DBO], which is the [Entity] in case of an [MapDBColumn]. */
    override val parent: Entity? = entity

    /** Internal reference to the [Store] underpinning this [MapDBColumn]. */
    private var store: StoreWAL = try {
        StoreWAL.make(file = this.path.toString(), volumeFactory = MappedFileVol.FACTORY, fileLockWait = this.parent!!.parent!!.parent.config.lockTimeout)
    } catch (e: DBException) {
        throw DatabaseException("Failed to open column at '$path': ${e.message}'")
    }

    /** Internal reference to the [Header] of this [MapDBColumn]. */
    private val header
        get() = store.get(HEADER_RECORD_ID, ColumnHeaderSerializer) ?: throw DatabaseException.DataCorruptionException("Failed to open header of column '$fqn'!'")

    /**
     * Getter for the [ColumnType] of this [MapDBColumn].
     *
     * @return [ColumnType] of this [MapDBColumn].
     */
    override val type: ColumnType<T>
        get() = this.header.type as ColumnType<T>

    /**
     * Getter for the size of this [MapDBColumn].
     *
     * @return size of this [MapDBColumn].
     */
    override val size: Int
        get() = this.header.size

    /**
     * Getter for the nullability property of this [MapDBColumn].
     *
     * @return Nullability property of this [MapDBColumn].
     */
    override val nullable: Boolean
        get() = this.header.nullable

    /**
     * Getter for this [MapDBColumn]'s [ColumnDef].
     *
     * @return [ColumnDef] for this [MapDBColumn]
     */
    override val columnDef: ColumnDef<T>
        get() = this.header.let { ColumnDef(this.name, it.type as ColumnType<T>, it.size, it.nullable) }

    /**
     * Status indicating whether this [MapDBColumn] is open or closed.
     */
    @Volatile
    override var closed: Boolean = false
        private set

    /** A internal lock that is used to synchronize [MapDBColumn.Tx]s affecting this [MapDBColumn]. */
    private val txLock = ReentrantReadWriteLock()

    /** A internal lock that is used to synchronize closing of an [MapDBColumn] with running [MapDBColumn.Tx]. */
    private val globalLock = ReentrantReadWriteLock()

    /**
     * Closes the [MapDBColumn]. Closing an [MapDBColumn] is a delicate matter since ongoing [MapDBColumn.Tx]  are involved.
     * Therefore, access to the method is mediated by an global [MapDBColumn] wide lock.
     */
    override fun close() = this.globalLock.write {
        this.store.close()
        this.closed = true
    }

    /**
     * Creates a new [MapDBColumn.Tx] and returns it.
     *
     * @param readonly True, if the resulting [MapDBColumn.Tx] should be a read-only transaction.
     * @param tid The ID for the new [MapDBColumn.Tx]
     *
     * @return A new [ColumnTransaction] object.
     */
    override fun newTransaction(readonly: Boolean, tid: UUID): ColumnTransaction<T> = Tx(readonly, tid)

    /**
     * Companion object with some important constants.
     */
    companion object {
        /** Record ID of the [ColumnHeader]. */
        private const val HEADER_RECORD_ID: Long = 1L

        /**
         * Initializes a new, empty [MapDBColumn]
         *
         * @param parent The folder that contains the data file.
         * @param definition The [ColumnDef] that specified the [MapDBColumn]
         */
        fun initialize(definition: ColumnDef<*>, path: Path) {
            val store = StoreWAL.make(file = path.resolve("col_${definition.name}.db").toString(), volumeFactory = MappedFileVol.FACTORY)
            store.put(ColumnHeader(type = definition.type, size = definition.size, nullable = definition.nullable), ColumnHeaderSerializer)
            store.commit()
            store.close()
        }
    }

    /**
     * A [Transaction] that affects this [MapDBColumn].
     */
    inner class Tx constructor(override val readonly: Boolean, override val tid: UUID): ColumnTransaction<T> {
        /** Flag indicating whether or not this [Entity.Tx] was closed */
        @Volatile override var status: TransactionStatus = TransactionStatus.CLEAN
            private set

        /** The [Serializer] used for de-/serialization of [MapDBColumn] entries. */
        val serializer = this@MapDBColumn.type.serializer(this@MapDBColumn.columnDef.size)

        /** Tries to acquire a global read-lock on the [MapDBColumn]. */
        init {
            if (this@MapDBColumn.closed) {
                throw TransactionException.TransactionDBOClosedException(tid)
            }
            this@MapDBColumn.globalLock.readLock().lock()
        }

        /**
         * Commits all changes made through this [Tx] since the last commit or rollback.
         */
        @Synchronized
        override fun commit() {
            if (this.status == TransactionStatus.DIRTY) {
                this@MapDBColumn.store.commit()
                this.status = TransactionStatus.CLEAN
                this@MapDBColumn.txLock.writeLock().unlock()
            }
        }

        /**
         * Rolls all changes made through this [Tx] back to the last commit. Can only be executed, if [Tx] is
         * in status [TransactionStatus.DIRTY] or [TransactionStatus.ERROR].
         */
        @Synchronized
        override fun rollback() {
            if (this.status == TransactionStatus.DIRTY || this.status == TransactionStatus.ERROR) {
                this@MapDBColumn.store.rollback()
                this.status = TransactionStatus.CLEAN
                this@MapDBColumn.txLock.writeLock().unlock()
            }
        }

        /**
         * Closes this [Tx] and relinquishes the associated [ReentrantReadWriteLock].
         */
        @Synchronized
        override fun close() {
            if (this.status != TransactionStatus.CLOSED) {
                if (this.status == TransactionStatus.DIRTY || this.status == TransactionStatus.ERROR) {
                    this@MapDBColumn.store.rollback()
                    this@MapDBColumn.txLock.writeLock().unlock()
                }
                this.status = TransactionStatus.CLOSED
                this@MapDBColumn.globalLock.readLock().unlock()
            }
        }

        /**
         * Gets and returns an entry from this [MapDBColumn].
         *
         * @param tupleId The ID of the desired entry
         * @return The desired entry.
         *
         * @throws DatabaseException If the tuple with the desired ID doesn't exist OR is invalid.
         */
        override fun read(tupleId: Long): T? = this@MapDBColumn.txLock.read {
            checkValidOrThrow()
            checkValidTupleId(tupleId)
            return this@MapDBColumn.store.get(tupleId, this.serializer)
        }

        /**
         * Gets and returns several entries from this [MapDBColumn].
         *
         * @param tupleIds The IDs of the desired entries
         * @return List of the desired entries.
         *
         * @throws DatabaseException If the tuple with the desired ID doesn't exist OR is invalid.
         */
        override fun readAll(tupleIds: Collection<Long>): Collection<T?> = this@MapDBColumn.txLock.read {
            checkValidOrThrow()
            tupleIds.map {
                checkValidTupleId(it)
                this@MapDBColumn.store.get(it, this.serializer)
            }
        }

        /**
         * Returns the number of entries in this [MapDBColumn]. Action acquires a global read dataLock for the [MapDBColumn].
         *
         * @return The number of entries in this [MapDBColumn].
         */
        override fun count(): Long = this@MapDBColumn.txLock.read {
            checkValidOrThrow()
            return this@MapDBColumn.header.count
        }

        /**
         * Applies the provided mapping function on each value found in this [MapDBColumn], returning a collection
         * of the desired output values.
         *
         * @param action The tasks that should be applied.
         * @return A collection of Pairs mapping the tupleId to the generated value.
         */
        override fun <R> map(action: (T?) -> R?): Collection<Tuple<R?>> = this@MapDBColumn.txLock.read {
            checkValidOrThrow()
            val list = mutableListOf<Tuple<R?>>()
            this@MapDBColumn.store.getAllRecids().forEach {
                if (it != HEADER_RECORD_ID) {
                    list.add(Tuple(it,action(this@MapDBColumn.store.get(it, this.serializer))))
                }
            }
            return list
        }

        /**
         * Applies the provided predicate function on each value found in this [MapDBColumn], returning a collection
         * of output values that pass the predicate's test (i.e. return true)
         *
         * @param predicate The tasks that should be applied.
         * @return A filtered collection [MapDBColumn] values that passed the test.
         */
        override fun filter(predicate: (T?) -> Boolean): Collection<Tuple<T?>> = this@MapDBColumn.txLock.read {
            checkValidOrThrow()
            val list = mutableListOf<Tuple<T?>>()
            val recordIds = this@MapDBColumn.store.getAllRecids()
            if (recordIds.next() != HEADER_RECORD_ID) {
                throw TransactionException.TransactionValidationException(this.tid, "The column '${this@MapDBColumn.fqn}' does not seem to contain a valid header record!")
            }
            recordIds.forEach {
                val data = this@MapDBColumn.store.get(it, this.serializer)
                if (predicate(data)) list.add(Tuple(it, data))
            }
            return list
        }

        /**
         * Applies the provided function on each element found in this [MapDBColumn]. The provided function cannot not change
         * the data stored in the [MapDBColumn]!
         *
         * @param action The function to apply to each [MapDBColumn] entry.
         */
        override fun forEach(action: (Long,T?) -> Unit) = this@MapDBColumn.txLock.read {
            checkValidOrThrow()
            val recordIds = this@MapDBColumn.store.getAllRecids()
            if (recordIds.next() != HEADER_RECORD_ID) {
                throw TransactionException.TransactionValidationException(this.tid, "The column '${this@MapDBColumn.fqn}' does not seem to contain a valid header record!")
            }
            recordIds.forEachRemaining {
                action(it,this@MapDBColumn.store.get(it, this.serializer))
            }
        }

        /**
         * Applies the provided function on each element found in this [MapDBColumn]. The provided function cannot not change
         * the data stored in the [MapDBColumn]!
         *
         * @param action The function to apply to each [MapDBColumn] entry.
         * @param parallelism The desired amount of parallelism (i.e. the number of co-routines to spawn).
         */
        override fun parallelForEach(action: (Long,T?) -> Unit, parallelism: Short) = runBlocking {
            checkValidOrThrow()
            val list = ArrayList<Long>(this@Tx.count().toInt()) /** TODO: only works if column contains at most MAX_INT entries */
            val recordIds = this@MapDBColumn.store.getAllRecids()
            if (recordIds.next() != HEADER_RECORD_ID) {
                throw TransactionException.TransactionValidationException(this@Tx.tid, "The column '${this@MapDBColumn.fqn}' does not seem to contain a valid header record!")
            }
            this@MapDBColumn.store.getAllRecids().forEachRemaining{ list.add(it) }
            val block = list.size / parallelism
            val jobs = Array(parallelism.toInt()) {
                GlobalScope.launch {
                    for (x in ((it * block) until Math.min((it * block) + block, list.size))) {
                        val tupleId = list[x]
                        action(tupleId,this@MapDBColumn.store.get(tupleId, this@Tx.serializer))
                    }
                }
            }
            jobs.forEach { it.join() }
        }

        /**
         * Inserts a new record in this [MapDBColumn]. This tasks will set this [MapDBColumn.Tx] to [TransactionStatus.DIRTY]
         * and acquire a column-wide write lock until the [MapDBColumn.Tx] either commit or rollback is issued.
         *
         * @param record The record that should be inserted. Can be null!
         * @return The tupleId of the inserted record OR the allocated space in case of a null value.
         */
        override fun insert(record: T?): Long = try {
            acquireWriteLock()
            val tupleId = if (record == null) {
                this@MapDBColumn.store.preallocate()
            } else {
                this@MapDBColumn.store.put(record, this.serializer)
            }

            /* Update header. */
            val header = this@MapDBColumn.header
            header.count += 1
            header.modified = System.currentTimeMillis()
            store.update(HEADER_RECORD_ID, header, ColumnHeaderSerializer)
            tupleId
        } catch (e: DBException) {
            this.status = TransactionStatus.ERROR
            throw TransactionException.TransactionStorageException(this.tid, e.message ?: "Unknown")
        }

        /**
         * Inserts a list of new records in this [MapDBColumn]. This tasks will set this [MapDBColumn.Tx] to [TransactionStatus.DIRTY]
         * and acquire a column-wide write lock until the [MapDBColumn.Tx] either commit or rollback is issued.
         *
         * @param records The records that should be inserted. Can contain null values!
         * @return The tupleId of the inserted record OR the allocated space in case of a null value.
         */
        override fun insertAll(records: Collection<T?>): Collection<Long> = try {
            acquireWriteLock()


            val tupleIds = records.map {
                if (it == null) {
                this@MapDBColumn.store.preallocate()
            } else {
                this@MapDBColumn.store.put(it, serializer)
            } }

            /* Update header. */
            val header = this@MapDBColumn.header
            header.count += records.size
            header.modified = System.currentTimeMillis()
            store.update(HEADER_RECORD_ID, header, ColumnHeaderSerializer)
            tupleIds
        } catch (e: DBException) {
            this.status = TransactionStatus.ERROR
            throw TransactionException.TransactionStorageException(this.tid, e.message ?: "Unknown")
        }

        /**
         * Updates the entry with the specified tuple ID and sets it to the new value. This tasks will set this [MapDBColumn.Tx]
         * to [TransactionStatus.DIRTY] and acquire a column-wide write lock until the [MapDBColumn.Tx] either commit or rollback is issued.
         *
         * @param tupleId The ID of the record that should be updated
         * @param value The new value.
         */
        override fun update(tupleId: Long, value: T?) = try {
            acquireWriteLock()
            checkValidTupleId(tupleId)
            this@MapDBColumn.store.update(tupleId, value, this.serializer)
        } catch (e: DBException) {
            this.status = TransactionStatus.ERROR
            throw TransactionException.TransactionStorageException(this.tid, e.message ?: "Unknown")
        }

        /**
         * Updates the entry with the specified tuple ID and sets it to the new value. This tasks will set this [MapDBColumn.Tx]
         * to [TransactionStatus.DIRTY] and acquire a column-wide write lock until the [MapDBColumn.Tx] either commit or rollback is issued.
         *
         * @param tupleId The ID of the record that should be updated
         * @param value The new value.
         * @param expected The value expected to be there.
         */
        override fun compareAndUpdate(tupleId: Long, value: T?, expected: T?): Boolean = try {
            acquireWriteLock()
            checkValidTupleId(tupleId)
            this@MapDBColumn.store.compareAndSwap(tupleId, expected, value, this.serializer)
        } catch (e: DBException) {
            this.status = TransactionStatus.ERROR
            throw TransactionException.TransactionStorageException(this.tid, e.message ?: "Unknown")
        }

        /**
         * Deletes a record from this [MapDBColumn]. This tasks will set this [MapDBColumn.Tx] to [TransactionStatus.DIRTY]
         * and acquire a column-wide write lock until the [MapDBColumn.Tx] either commit or rollback is issued.
         *
         * @param tupleId The ID of the record that should be deleted
         */
        override fun delete(tupleId: Long) = try {
            acquireWriteLock()
            checkValidTupleId(tupleId)
            this@MapDBColumn.store.delete(tupleId, this.serializer)

            /* Update header. */
            val header = this@MapDBColumn.header
            header.count -= 1
            header.modified = System.currentTimeMillis()
            this@MapDBColumn.store.update(HEADER_RECORD_ID, header, ColumnHeaderSerializer)
        } catch (e: DBException) {
            this.status = TransactionStatus.ERROR
            throw TransactionException.TransactionStorageException(this.tid, e.message ?: "Unknown")
        }

        /**
         * Deletes all the specified records from this [MapDBColumn]. This tasks will set this [MapDBColumn.Tx] to [TransactionStatus.DIRTY]
         * and acquire a column-wide write lock until the [MapDBColumn.Tx] either commit or rollback is issued.
         *
         * @param tupleIds The IDs of the records that should be deleted.
         */
        override fun deleteAll(tupleIds: Collection<Long>) = try {
            acquireWriteLock()
            tupleIds.forEach{
                checkValidTupleId(it)
                this@MapDBColumn.store.delete(it, this.serializer)
            }

            /* Update header. */
            val header = this@MapDBColumn.header
            header.count -= tupleIds.size
            header.modified = System.currentTimeMillis()
            store.update(HEADER_RECORD_ID, header, ColumnHeaderSerializer)
        } catch (e: DBException) {
            this.status = TransactionStatus.ERROR
            throw TransactionException.TransactionStorageException(this.tid, e.message ?: "Unknown")
        }

        /**
         * Checks if the provided tupleID is valid. Otherwise, an exception will be thrown.
         */
        private fun checkValidTupleId(tupleId: Long) {
            if ((tupleId < 0L) or (tupleId == HEADER_RECORD_ID)) {
                throw TransactionException.InvalidTupleId(tid, tupleId)
            }
        }

        /**
         * Checks if this [MapDBColumn.Tx] is still open. Otherwise, an exception will be thrown.
         */
        @Synchronized
        private fun checkValidOrThrow() {
            if (this.status == TransactionStatus.CLOSED) throw TransactionException.TransactionClosedException(tid)
            if (this.status == TransactionStatus.ERROR) throw TransactionException.TransactionInErrorException(tid)
        }

        /**
         * Tries to acquire a write-lock. If method fails, an exception will be thrown
         */
        @Synchronized
        private fun acquireWriteLock() {
            if (this.readonly) throw TransactionException.TransactionReadOnlyException(tid)
            if (this.status == TransactionStatus.CLOSED) throw TransactionException.TransactionClosedException(tid)
            if (this.status == TransactionStatus.ERROR) throw TransactionException.TransactionInErrorException(tid)
            if (this.status != TransactionStatus.DIRTY) {
                if (this@MapDBColumn.txLock.writeLock().tryLock()) {
                    this.status = TransactionStatus.DIRTY
                } else {
                    throw TransactionException.TransactionWriteLockException(this.tid)
                }
            }
        }
    }
}
