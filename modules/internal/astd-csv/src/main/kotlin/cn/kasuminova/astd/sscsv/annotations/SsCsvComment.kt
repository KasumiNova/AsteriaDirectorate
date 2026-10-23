package cn.kasuminova.astd.sscsv.annotations

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class SsCsvComment(val value: String)
