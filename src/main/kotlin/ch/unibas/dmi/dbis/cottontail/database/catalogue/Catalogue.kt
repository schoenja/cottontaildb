package ch.unibas.dmi.dbis.cottontail.database.catalogue

import ch.unibas.dmi.dbis.cottontail.config.Config
import ch.unibas.dmi.dbis.cottontail.database.entity.Entity
import ch.unibas.dmi.dbis.cottontail.database.general.DBO
import ch.unibas.dmi.dbis.cottontail.database.schema.Schema
import ch.unibas.dmi.dbis.cottontail.database.schema.SchemaHeader
import ch.unibas.dmi.dbis.cottontail.database.schema.SchemaHeaderSerializer
import ch.unibas.dmi.dbis.cottontail.model.exceptions.DatabaseException
import ch.unibas.dmi.dbis.cottontail.utilities.name.*
import org.mapdb.*

import java.io.IOException
import java.lang.IllegalArgumentException

import java.nio.file.Files
import java.nio.file.Path

import java.util.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.stream.Collectors

import kotlin.concurrent.read
import kotlin.concurrent.write


/**
 * The main catalogue in Cottontail DB. It contains references to all the [Schema]s managed by Cottontail
 * and is the main way of accessing these [Schema]s and creating new ones.
 *
 * @see Schema
 *
 * @author Ralph Gasser
 * @version 1.1
 */
class Catalogue(val config: Config): DBO {
    /** Root to Cottontail DB root folder. */
    override val path: Path = config.root

    /** Constant name of the [Catalogue] object. */
    override val name: Name = Name("warren")

    /** Constant name of the [Catalogue] object. */
    override val fqn: Name = this.name

    /** Constant parent [DBO], which is null in case of the [Catalogue]. */
    override val parent: DBO? = null

    /** A lock used to mediate access to this [Catalogue]. */
    private val lock = ReentrantReadWriteLock()

    /** A in-memory registry of all the [Schema]s contained in this [Catalogue]. When a [Catalogue] is opened, all the [Schema]s will be loaded. */
    private val registry: HashMap<Name, Schema> = HashMap()

    /** The [StoreWAL] that contains the Cottontail DB catalogue. */
    private val store: CottontailStoreWAL = path.let {
        val file = this.path.resolve(FILE_CATALOGUE)
        if (Files.exists(file)) {
            openStore(file)
        } else {
            initStore(file)
        }
    }

    /** Reference to the [CatalogueHeader] of the [Catalogue]. Accessing it will read right from the underlying store. */
    private val header: CatalogueHeader
        get() = this.store.get(HEADER_RECORD_ID, CatalogueHeaderSerializer) ?: throw DatabaseException.DataCorruptionException("Failed to open Cottontail DB catalogue header!")

    /** List of [Schema] names registered in this [Catalogue]. */
    val schemas: Collection<Name>
        get() = this.lock.read { this.registry.keys.toList() }

    /** Size of this [Catalogue] in terms of [Schema]s it contains. */
    val size: Int
        get() = this.lock.read { this.header.schemas.size }

    /** Status indicating whether this [Catalogue] is open or closed. */
    @Volatile
    override var closed: Boolean = false
        private set

    /**
     * Closes the [Catalogue] and all objects contained within.
     */
    override fun close() = this.lock.write {
        this.registry.forEach { (_, v) -> v.close()}
        this.store.close()
        this.closed = true
    }

    /**
     * Handles finalization of the [Catalogue].
     */
    @Synchronized
    protected fun finalize() {
        if (!this.closed) {
            /* Should not happen! */
            this.close()
        }
    }

    /** Initialization logic for [Catalogue]. */
    init {
        val header = this.header
        for (sid in header.schemas) {
            val schema = this.store.get(sid, CatalogueEntrySerializer) ?: throw DatabaseException.DataCorruptionException("Failed to open Cottontail DB catalogue entry!")
            val path = this.path.resolve("schema_${schema.name}")
            if (!Files.exists(path)) {
                throw DatabaseException.DataCorruptionException("Broken catalogue entry for schema '${schema.name}'. Path $path does not exist!")
            }
            val s = Schema(Name(schema.name), path, this)
            this.registry[s.name] = s
        }
    }

    /**
     * Creates a new, empty [Schema] with the given name and [Path]
     *
     * @param name The name of the new [Name].
     * @param data The path where this new [Schema] will be located. Defaults to a path relative to the current one.
     */
    fun createSchema(name: Name) = this.lock.write {
        /* Check the type of name and normalize it. */
        if (name.type != NameType.SIMPLE) {
            throw IllegalArgumentException("The provided name '$name' is of type '${name.type} and cannot be used to access a schema.")
        }

        /* Check if schema with that name exists. */
        if (this.registry.containsKey(name)) {
            throw DatabaseException.SchemaAlreadyExistsException(name)
        }

        /* Create empty folder for entity. */
        val path = this.path.resolve("schema_$name")
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path)
            } else {
                throw DatabaseException("Failed to create schema '$name'. Data directory '$path' seems to be occupied.")
            }
        } catch (e: IOException) {
            throw DatabaseException("Failed to create schema '$name' due to an IO exception: ${e.message}")
        }

        /* Generate the store for the new schema and update catalogue. */
        try {
            /* Create new store. */
            val store = StoreWAL.make(file = path.resolve(Schema.FILE_CATALOGUE).toString(), volumeFactory = this.config.volumeFactory, fileLockWait = this.config.lockTimeout)
            store.put(SchemaHeader(), SchemaHeaderSerializer)
            store.commit()
            store.close()

            /* Update catalogue. */
            val sid = this.store.put(CatalogueEntry(name.name), CatalogueEntrySerializer)

            /* Update header. */
            val new = this.header.let { CatalogueHeader(it.size + 1, it.created, System.currentTimeMillis(), it.schemas.copyOf(it.schemas.size + 1)) }
            new.schemas[new.schemas.size-1] = sid
            this.store.update(HEADER_RECORD_ID, new, CatalogueHeaderSerializer)
            this.store.commit()
        } catch (e: DBException) {
            this.store.rollback()
            val pathsToDelete = Files.walk(path).sorted(Comparator.reverseOrder()).collect(Collectors.toList())
            pathsToDelete.forEach { Files.delete(it) }
            throw DatabaseException("Failed to create schema '$name' due to a storage exception: ${e.message}")
        }

        /* Add schema to local map. */
        this.registry[name] = Schema(name, path, this)
    }

    /**
     * Drops an existing [Schema] with the given name. <strong>Warning:</strong> Dropping a [Schema] deletes all the files associated with it [Schema]!
     *
     * @param name The name of the [Schema] to be dropped.
     */
    fun dropSchema(name: Name) = this.lock.write {
        /* Check the type of name. */
        if (name.type != NameType.SIMPLE) {
            throw IllegalArgumentException("The provided name '$name' is of type '${name.type} and cannot be used to access a schema.")
        }

        /* Try to close the schema. Open registry cannot be dropped. */
        (this.registry[name] ?: throw DatabaseException.SchemaDoesNotExistException(name)).close()

        /* Extract the catalogue entry. */
        val catalogueEntry = this.header.schemas
                .map { Pair(it, this.store.get(it, CatalogueEntrySerializer) ?: throw DatabaseException.DataCorruptionException("Failed to read Cottontail DB catalogue entry for SID=$it!")) }
                .find { Name(it.second.name) == name } ?: throw DatabaseException("Failed to drop schema '$name'. Did not find a Cottontail DB catalogue entry for schema $name!")

        /* Remove catalogue entry + update header. */
        try {
            this.store.delete(catalogueEntry.first, CatalogueEntrySerializer)
            val new = this.header.let { CatalogueHeader(it.size - 1, it.created, System.currentTimeMillis(), it.schemas.filter { it != catalogueEntry.first }.toLongArray()) }
            this.store.update(HEADER_RECORD_ID, new, CatalogueHeaderSerializer)
            this.store.commit()
        } catch (e: DBException) {
            this.store.rollback()
            throw DatabaseException("Failed to drop schema '$name' due to a storage exception: ${e.message}")
        }

        /* Remove schema from registry. */
        this.registry.remove(name)

        /* Delete files that belong to the schema. */
        val path = this.path.resolve("schema_$name")
        val pathsToDelete = Files.walk(path).sorted(Comparator.reverseOrder()).collect(Collectors.toList())
        pathsToDelete.forEach { Files.delete(it) }
    }

    /**
     * Returns the [Schema] for the given [Name]. The provided [Name] must be of [NameType.SIMPLE]
     *
     * @param name [Name] of the [Schema].
     */
    fun schemaForName(name: Name): Schema = this.lock.read {
        require(name.type == NameType.SIMPLE) { "The provided name '$name' is of type '${name.type} and cannot be used to access a schema." }
        this.registry[name] ?: throw DatabaseException.SchemaDoesNotExistException(name)
    }

    /**
     * Opens the data store underlying this Cottontail DB [Catalogue]
     *
     * @param path The path to the data store file.
     * @return [StoreWAL] object.
     */
    private fun openStore(path: Path): CottontailStoreWAL = try {
        CottontailStoreWAL.make(file = path.toString(), volumeFactory = this.config.volumeFactory, fileLockWait = this.config.lockTimeout)
    } catch (e: DBException) {
        throw DatabaseException("Failed to open Cottontail DB catalogue: ${e.message}'.")
    }

    /**
     * Initializes a new Cottontail DB [Catalogue] under the given path.
     *
     * @param path The path to the data store file.
     * @return [StoreWAL] object.
     */
    private fun initStore(path: Path) = try {
        try {
            if (!Files.exists(path.parent)) {
                Files.createDirectories(path.parent)
            }
        } catch (e: IOException) {
            throw DatabaseException("Failed to create Cottontail DB catalogue due to an IO exception: ${e.message}")
        }

        /* Create and initialize new store. */
        val store = CottontailStoreWAL.make(file = config.root.resolve(Catalogue.FILE_CATALOGUE).toString(), volumeFactory = this.config.volumeFactory, fileLockWait = config.lockTimeout)
        store.put(CatalogueHeader(), CatalogueHeaderSerializer)
        store.commit()
        store
    } catch (e: DBException) {
        throw DatabaseException("Failed to initialize the Cottontail DB catalogue: ${e.message}'.")
    }

    /**
     * Companion object to [Catalogue]
     */
    companion object {
        /** ID of the schema header! */
        internal const val HEADER_RECORD_ID: Long = 1L

        /** Filename for the [Entity] catalogue.  */
        internal const val FILE_CATALOGUE = "catalogue.db"
    }
}