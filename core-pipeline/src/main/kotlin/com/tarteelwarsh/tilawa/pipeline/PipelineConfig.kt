package com.tarteelwarsh.tilawa.pipeline

/** Miroir des constantes de Dani_app/api/config.py effectivement utilisees par process_chunk(). */
object PipelineConfig {
    const val SAMPLE_RATE = 16000
    const val MAX_BUFFER_SECONDS = 30
    const val MAX_BUFFER_SAMPLES = MAX_BUFFER_SECONDS * SAMPLE_RATE
    const val PAD_SAMPLES = 1600          // ~100ms - padding autour du segment de parole detecte + seuil wasl
    const val MIN_SEGMENT_SAMPLES = 1600  // ~100ms - segments plus courts ignores
    // Lowered from 8 on device evidence: the debug readout repeatedly showed
    // `why=short(7<8)` and `why=short(4<8)` while the text was already decoded and
    // waiting, so this gate - not the ASR - is what delayed printing after each
    // waqf. 8 was inherited, never measured. 4 still rejects the 1-2 char
    // fragments that make the Finder's window search unreliable.
    const val MIN_TILAWA_CHARS = 4        // transcriptions plus courtes ignorees
    const val SEGMENT_MATCH_TOLERANCE = 1600 // ~100ms - tolerance de wobble VAD pour la detection de `repeat`

    // Ecart DELIBERE par rapport a Dani_app, absent de Python : le segmenteur
    // n'y rescanne plus l'INTEGRALITE du buffer roulant (jusqu'a
    // MAX_BUFFER_SAMPLES = 30s) mais seulement ses SEGMENTER_WINDOW_SAMPLES
    // les plus recents - mesure reelle (pas un raisonnement de conception) :
    // le cout de l'inference segmenteur domine a 88% du temps de calcul
    // total et croit avec la duree du buffer (146ms a ~0.2s -> 863ms a
    // ~5.1s, sur le Note10) - voir app/README.md pour les chiffres complets.
    // Borner la fenetre borne directement cette croissance.
    //
    // Contrepartie de correction ACCEPTEE, pas cachee : `intervals[0]` (le
    // plus ancien segment de parole encore dans le buffer) peut avoir
    // commence avant cette fenetre pour un verset recite plus lentement que
    // SEGMENTER_WINDOW_SECONDS - son debut reel ne sera alors jamais vu par
    // le segmenteur, contrairement au comportement Dani_app (buffer entier).
    // 4s (reduit depuis 10s apres mesure du plateau reel a 10s - toujours
    // trop lent pour le budget 100ms/chunk) pour reduire encore le cout,
    // au prix d'un risque de troncature accru pour les enonces longs.
    const val SEGMENTER_WINDOW_SECONDS = 4
    const val SEGMENTER_WINDOW_SAMPLES = SEGMENTER_WINDOW_SECONDS * SAMPLE_RATE

    // Deuxieme ecart DELIBERE, complementaire au fenetrage ci-dessus (pas un
    // remplacement) : le segmenteur n'est plus appele a CHAQUE chunk
    // core-audio (100ms) mais tous les SEGMENTER_CALL_INTERVAL_SAMPLES
    // (200ms) - reduit de moitie le nombre d'inferences segmenteur, sur un
    // cout deja borne par la fenetre. Contrepartie ACCEPTEE : la coupure
    // (endpointing) et le texte rapporte peuvent avoir jusqu'a ~100ms de
    // retard supplementaire par rapport a un appel a chaque chunk - le flux
    // audio/ASR, lui, n'est jamais throttle (core-asr voit toujours CHAQUE
    // chunk, inconditionnellement - seul l'appel au segmenteur saute un
    // chunk sur deux).
    const val SEGMENTER_CALL_INTERVAL_SAMPLES = SAMPLE_RATE / 5 // 200ms = 3200 echantillons a 16kHz

    // Seuil de l'EXPERIENCE "sans segmenteur" (voir ExperimentalCtcOnlyPipelineSession,
    // app/README.md) - PAS utilise par PipelineSession (production). Duree
    // (ms) sans symbole CTC non-blank au-dela de laquelle le proxy
    // experimental traite ça comme l'equivalent de `len(intervals) > 1`
    // (fin d'enonce). Historique : 400ms (valeur initiale) -> 200ms (mesure
    // reelle montrant que 400ms se declenchait trop rarement pendant la
    // parole continue fluide - voir app/README.md section 5) -> 300ms sur
    // decision explicite (avec AudioCapture.chunkSamples remonte a 300ms en
    // meme temps). Ajustable pour l'experience - pas une valeur validee
    // empiriquement.
    // FALLBACK end-of-utterance, used ONLY when the model has not marked a waqf.
    // The waqf-aware checkpoint was trained on clips whose FINAL waqf was stripped
    // (segment_raw_data.py does `buf.tilawa[:-1]`), so it never learned to emit "s"
    // for a trailing silence with nothing after it - it only ever saw "s" BETWEEN
    // segments. Stop reciting and no waqf is ever predicted, so without this the
    // utterance stays open indefinitely and its letters keep their provisional
    // shadow. 700ms: comfortably longer than any within-verse breath (p90 of real
    // pauses was 1.34s, and 36% of pauses are under 300ms).

    // Throttle explicite du Corrector pour l'EXPERIENCE "sans segmenteur",
    // demande explicitement apres mesure reelle : dans PipelineSession
    // (production), corrector.correct() est deja, de fait, throttle a
    // SEGMENTER_CALL_INTERVAL_SAMPLES (200ms) puisqu'il est place APRES le
    // `return null` du throttle d'appel segmenteur - un throttle "gratuit"
    // que ExperimentalCtcOnlyPipelineSession n'avait pas, et qui s'est
    // revele necessaire : sans lui, corrector.correct() etait rappele a
    // CHAQUE chunk (100ms) des que tilawa depassait MIN_TILAWA_CHARS, et son
    // propre cout (100-175ms/appel mesure) depassait a lui seul le budget
    // 100ms/chunk, faisant grimper backlogChunks jusqu'a 35 (voir
    // app/README.md section 5). Meme valeur que SEGMENTER_CALL_INTERVAL_SAMPLES
    // (200ms) pour rester comparable au throttle "gratuit" qu'il remplace -
    // ne throttle QUE l'appel au Corrector, jamais l'ingestion audio/ASR ni
    // la detection du waqf (quasi gratuite).
    const val EXPERIMENTAL_CORRECTOR_CALL_INTERVAL_SAMPLES = SAMPLE_RATE / 5 // 200ms
}
