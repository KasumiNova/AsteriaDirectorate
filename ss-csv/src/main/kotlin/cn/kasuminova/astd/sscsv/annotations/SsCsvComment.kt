package cn.kasuminova.astd.sscsv.annotations

/**
 * Optional human-facing comment for a CSV entry.
 *
 * NOTE: Starsector CSV parser may not accept comment lines; by default the generator
 * writes these comments into a sidecar .md file instead of the CSV itself.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class SsCsvComment(val value: String)
