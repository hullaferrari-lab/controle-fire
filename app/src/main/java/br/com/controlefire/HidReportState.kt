package br.com.controlefire

/** Local HID state; synchronize on this instance across transport sends and session changes. */
internal class HidReportState {
    private val reports = arrayOf(ByteArray(0), HidReports.released(1), HidReports.released(2))

    @Synchronized fun pressed(report: HidReports.Report) {
        require(report.id in 1..2 && report.bytes.size == reports[report.id].size)
        reports[report.id] = report.bytes.copyOf()
    }

    @Synchronized fun getReport(id: Int): ByteArray {
        require(id in 1..2)
        return reports[id].copyOf()
    }

    /** Reset even when permission or the receiver has already disappeared. */
    @Synchronized fun reset(): List<HidReports.Report> = (1..2).map { id ->
        reports[id] = HidReports.released(id)
        HidReports.Report(id, reports[id].copyOf())
    }
}
