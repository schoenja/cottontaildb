package ch.unibas.dmi.dbis.cottontail.execution.tasks.recordset.projection

import ch.unibas.dmi.dbis.cottontail.database.queries.Projection
import ch.unibas.dmi.dbis.cottontail.execution.cost.Costs
import ch.unibas.dmi.dbis.cottontail.execution.tasks.basics.ExecutionTask
import ch.unibas.dmi.dbis.cottontail.model.basics.ColumnDef
import ch.unibas.dmi.dbis.cottontail.model.recordset.Recordset
import ch.unibas.dmi.dbis.cottontail.model.values.DoubleValue
import com.github.dexecutor.core.task.Task
import com.github.dexecutor.core.task.TaskExecutionException

/**
 * A [Task] used during query execution. It takes a single [Recordset] and determines the minimum value of a specific [ColumnDef]. It thereby creates a 1x1 [Recordset].
 *
 * @author Ralph Gasser
 * @version 1.0
 */
internal class RecordsetMinProjectionTask(val projection: Projection, estimatedSize: Int = 1000): ExecutionTask("RecordsetMinProjectionTask") {

    /** The cost of this [RecordsetMaxProjectionTask] is constant */
    override val cost = estimatedSize * Costs.MEMORY_ACCESS_READ

    /**
     * Executes this [RecordsetCountProjectionTask]
     */
    override fun execute(): Recordset {
        assertUnaryInput()

        /* Get records from parent task. */
        val parent = this.first() ?: throw TaskExecutionException("Projection could not be executed because parent task has failed.")

        /* Calculate min(). */
        val column = projection.columns.first()
        val resultsColumn = ColumnDef.withAttributes(this.projection.fields[column.name] ?: "max(${column.name})", "DOUBLE")
        var min = 0.0
        val results = Recordset(arrayOf(resultsColumn))
        parent.forEach {
            when (val value = it[column]?.value) {
                is Byte -> min = Math.min(min, value.toDouble())
                is Short -> min = Math.min(min, value.toDouble())
                is Int -> min = Math.min(min, value.toDouble())
                is Long -> min = Math.min(min, value.toDouble())
                is Float -> min = Math.min(min, value.toDouble())
                is Double -> min = Math.min(min, value)
                else -> {}
            }
        }
        results.addRowUnsafe(arrayOf(DoubleValue(min)))
        return results
    }
}