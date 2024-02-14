package de.westnordost.streetcomplete.overlays.way_lit

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import de.westnordost.streetcomplete.R
import de.westnordost.streetcomplete.data.osm.edits.create.CreateNodeAction
import de.westnordost.streetcomplete.data.osm.edits.delete.DeletePoiNodeAction
import de.westnordost.streetcomplete.data.osm.mapdata.Node
import de.westnordost.streetcomplete.databinding.FragmentOverlayStreetLampBinding
import de.westnordost.streetcomplete.overlays.AbstractOverlayForm
import de.westnordost.streetcomplete.overlays.AnswerItem
import de.westnordost.streetcomplete.overlays.IAnswerItem
import de.westnordost.streetcomplete.util.getLocalesForFeatureDictionary

class StreetLanternForm : AbstractOverlayForm() {

    override val contentLayoutResId = R.layout.fragment_overlay_street_lamp
    private val binding by contentViewBinding(FragmentOverlayStreetLampBinding::bind)

    override val otherAnswers get() = listOfNotNull(
        createDeletePoiAnswer()
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setMarkerIcon(R.drawable.ic_preset_temaki_street_lamp_arm)

        val name = featureDictionary
            .byTags(mapOf("highway" to "street_lamp"))
            .forLocale(*getLocalesForFeatureDictionary(resources.configuration))
            .find()
            .firstOrNull()
            ?.name
        binding.featureTextView.text = name
    }

    private fun createDeletePoiAnswer(): IAnswerItem? {
        val node = element as? Node ?: return null
        return AnswerItem(R.string.quest_generic_answer_does_not_exist) { confirmDelete(node) }
    }

    private fun confirmDelete(node: Node) {
        AlertDialog.Builder(requireContext())
            .setMessage(R.string.osm_element_gone_description)
            .setPositiveButton(R.string.osm_element_gone_confirmation) { _, _ -> applyEdit(DeletePoiNodeAction(node)) }
            .setNeutralButton(R.string.leave_note) { _, _ -> composeNote(node) }
            .show()
    }

    override fun hasChanges(): Boolean = element == null

    override fun isFormComplete(): Boolean = true

    override fun onClickOk() {
        if (element == null) {
            applyEdit(CreateNodeAction(geometry.center, mapOf("highway" to "street_lamp")))
        }
    }
}
