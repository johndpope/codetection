(MODULE TOOLLIB-CODETECTION)

(include "QobiScheme.sch")
(include "toollib-c-macros.sch")
(include "toollib-codetection.sch")
(c-include "codetectionlib-c.h")
(c-include "idealib-c.h")
(c-include "inference.h")

;;(set! *program* "codetection")
;;(set! *panic?* #f)


;;;;UNCOMMENT IF NOT USING IN DSCI
;; ;;;; ffmpeg, imlib, and pnm scheme->c bindings

;; (define-structure imlib-image handle)
;; (c-ffi:add-custom-type imlib-image pointer make-imlib-image imlib-image-handle)

;; (define ffmpeg-open-video
;;  (c-function pointer ("ffmpeg_open_video" string)))

;; (define ffmpeg-video-frame-data-as-imlib
;;  (c-function imlib-image ("ffmpeg_get_frame_as_imlib" pointer)))

;; (define ffmpeg-close-video
;;  (c-function void ("ffmpeg_close_and_free_video" pointer)))

;; (define (ffmpeg-next-frame! video)
;;  (not (= ((c-function int ("ffmpeg_next_frame" pointer)) video) 0)))

;; (define imlib-context-set-image!
;;  (c-function void ("imlib_context_set_image" imlib-image)))

(define (imlib:free-image-and-decache image)
 (imlib-context-set-image! image)
 ((c-function void ("imlib_free_image_and_decache"))))

(define (imlib:save-image image file)
 (imlib-context-set-image! image)
 ((c-function void ("imlib_save_image" pointer)) file))

;; (define (imlib:height image)
;;  (imlib-context-set-image! image)
;;  ((c-function int ("imlib_image_get_height"))))

;; (define (imlib:width image)
;;  (imlib-context-set-image! image)
;;  ((c-function int ("imlib_image_get_width"))))

;; (define (imlib:clone image)
;;  (imlib-context-set-image! image)
;;  ((c-function imlib-image ("imlib_clone_image"))))

;; (define (imlib:data-ptr-read-only image)
;;  (imlib-context-set-image! image)
;;  ((c-function pointer ("imlib_image_get_data_for_reading_only"))))

;; ;;;;;;;;; auxiliary 

;; (define (matrix->list-of-lists m)
;;  (vector->list (map-vector vector->list m)))

;; ;;;;;;;;; timing

;; (define (time-thunk format-string thunk)
;;  (let* ((start (current-time)
;; 	(result (thunk))
;; 	(end (current-time))
;; 	(precision 2)
;; 	(length
;; 	 (if (< (- end start) 1) ;; 0.xx
;; 	     (+ 2 precision)
;; 	     (inexact->exact
;; 	      (floor (+ (+ (/ (log (- end start))
;; 			      (log 10)) 1) 1 precision))))))
;;   (format #t format-string
;; 	  (number->string-of-length-and-precision (- end start) length precision))
;;   result))

;; (define *time-depth* 0)

;; (define (current-time)
;;  (let ((time-file (string-append "/tmp/time-" (number->string (getpid)))))
;;   (system (format #f "date +'%s %N' > ~a" time-file))
;;   (let ((t (first (map fields (read-file time-file)))))
;;    (rm time-file)
;;    (string->number (string-append (first t) "." (second t))))))

;; (define-macro time
;;  (lambda (form expander)
;;   (expander `(time-thunk ,(second form) (lambda () ,(third form))) expander)))

;; (define-macro time-code
;;  (lambda (form expander)
;;   (expander `(time-thunk (format #f "~a~~a : ~a~%"
;; 				 (make-string *time-depth* #\+)
;; 				 ,(format #f "~a" (second form)))
;; 			 (lambda ()
;; 			  (set! *time-depth* (+ *time-depth* 1))
;; 			  (let ((ret ,(second form)))
;; 			   (set! *time-depth* (- *time-depth* 1))
;; 			   ret)))
;; 	    expander)))

;;;;;;;;; compute proposals from matlab functions ;;;;;;;;;;;;

(define (video->frames downsample video-path)
 (let ((ffmpeg-video (ffmpeg-open-video video-path)))
  (let loop ((frames '())
	     (start #t)
	     (i 0))
   (if (or start (ffmpeg-next-frame! ffmpeg-video))
       (if (zero? (modulo i downsample))
	   (loop (cons (ffmpeg-video-frame-data-as-imlib ffmpeg-video) frames) #f (+ i 1))
	   (loop frames #f (+ i 1)))
       (begin
	(ffmpeg-close-video ffmpeg-video)
	(reverse frames))))))

(define (proposals&similarity-with-frames top-k box-size frames)
 (let* ((tt (length frames))
	(one-frame (first frames))
	(height (imlib:height one-frame))
	(width (imlib:width one-frame))
	;; (prop-sim-path "/home/sbroniko/codetection/source/sentence-codetection/proposals_and_similarity")
	)
  (start-matlab!)
  (matlab (format #f "frames=zeros(~a,~a,~a,~a,'uint8');" height width 3 tt))
  ;; convert frames to matlab matrix
  ;;(format #f "before for-each-indexed")
  (for-each-indexed
   (lambda (frame i)
    ;;(format #t "converting imlib ~a/~a to matlab matrix...~%" i tt)
    (with-temporary-file
     "/tmp/imlib-frame.ppm"
     (lambda (tmp-frame)
      ;; write scheme frame to file
      (imlib:save-image frame tmp-frame)
      ;; read file as matlab frame
      (matlab (format #f "frame=imread('~a');" tmp-frame))
      (matlab (format #f "frames(:,:,:,~a)=uint8(frame);" (+ i 1)))))
    (imlib:free-image-and-decache frame))    
   frames)
  ;;(matlab (format #f "imshow(frames(:,:,:,20));"))
  ;; call proposals_and_similarity
  (matlab (format #f "[bboxes,simi]=proposals_and_similarity(~a,frames,~a);"
		  top-k box-size))
  ;; convert matlab variables to scheme
  (list (map-n (lambda (t)
		(matlab (format #f "tmp=bboxes(:,:,~a);" (+ t 1)))
		(matlab-get-variable "tmp"))
	       tt)
	(map-n (lambda (t)
		(matlab (format #f "tmp=simi(:,:,~a);" (+ t 1)))
		(matlab-get-variable "tmp"))
	       (- tt 1)))))

(define (proposals&similarity top-k box-size downsample video-path)
 (proposals&similarity-with-frames top-k box-size
				   (video->frames downsample video-path)))

;;;;;;;;;; codetection inference with BP ;;;;;;;;;;

;; (define bp-object-inference
;;  (c-function double ("bp_object_inference" pointer pointer int int pointer)))


(define bp-object-inference ;;DON'T FUCK THIS ONE UP
 (c-function double ("bp_object_inference" pointer pointer int int
		     double double int pointer)))

;;------these don't work
(define bp-label-inference
 (c-function double ("bp_label_inference" int int
		     double pointer pointer)))

(define bruteforce-label-inference
 (c-function double ("bruteforce_label_inference" int int
		     double pointer pointer)))


(define bp-label-inference-original
 (c-function double ("bp_label_inference_original" int int
		     double double pointer double pointer)))


(define label-inference-test-with-noise
 (c-function double ("label_inference_test_with_noise" int int
		     double pointer pointer int float)))


(define label-inference-with-noise
 (c-function double ("label_inference_with_noise" int int
		     double pointer pointer int double double)))
;;-----------

;;Compiling labeling.sc stuff into dsci

;;functions for branch and bound

(define (build-table sim num-labels)
 (map-n-vector
  (lambda (i)
   (map-n-vector
    (lambda (j)
     (if (= i j)
	 (* -1 (log sim))
	 (* -1 (log (- 1 sim)))))
    num-labels))
   num-labels))

(define (build-table-matrix sim-matrix num-labels)
 (let ((r (matrix-rows sim-matrix))
       (c (matrix-columns sim-matrix)))
  (map-n-vector
   (lambda (i)
    (map-n-vector
     (lambda (j)
      (let* ((sim1 (matrix-ref sim-matrix i j))
	     (sim2 (matrix-ref sim-matrix j i))
	     (sim-value (/ (+ sim1 sim2) 2.0)))
       (list (min (-(log sim-value)) (-(log (- 1 sim-value))))
	     0
	     (build-table sim-value num-labels))))
     c))
   r)))


(define (minimum-vector v)
 (let ((m infinity)
       (len (vector-length v)))
  (let loop
    ((i 0)
     (m m))
   (if (>= i len)
       m
       (loop (+ i 1)
	     (min m (vector-ref v i)))))))

(define (maximum-vector v)
 (let ((m minus-infinity)
       (len (vector-length v)))
  (let loop
    ((i 0)
     (m m))
   (if (>= i len)
       m
       (loop (+ i 1)
	     (max m (vector-ref v i)))))))


(define (best-possible labels table-matrix num-peaks)
 (let ((labels (append labels
		       (map-n (lambda (i) #f) (- num-peaks (length labels))))))
  (reduce +
	 (join 
	  (map-indexed
	   (lambda (label1 i)
	    (map-indexed
	     (lambda (label2 j)
		    (if (< i j) ;;so we're not adding everything twice
			(cond ((and label1 label2)
			       ;;both labels set, so score it
			       (matrix-ref 
				(third (matrix-ref table-matrix i j))
				label1 label2))
			      ((and (not label1) (not label2))
			       ;;neither label set, so get best possible for that pair
			       (first (matrix-ref table-matrix i j))
			       ;; (minimum-vector
			       ;; 	(map-vector
			       ;; 	 (lambda (x)
			       ;; 	  (minimum-vector x))
			       ;; 	 (third (matrix-ref table-matrix i j))))
			       )
			      (else
			       ;;label1 set but label2 not, so get best possible
			       (first (matrix-ref table-matrix i j))
			       ;; (minimum-vector
			       ;; 	(vector-ref
			       ;; 	 (third (matrix-ref table-matrix i j))
			       ;; 	 label1))
			       ))
			      0)) labels)) labels))
	 0)))

(define (score labels table-matrix)
 (reduce +
	 (join 
	  (map-indexed
	   (lambda (label1 i)
	    (map-indexed
	     (lambda (label2 j)
		    (if (< i j) ;;so we're not adding everything twice
			(matrix-ref 
			 (third (matrix-ref table-matrix i j))
			 label1 label2)
			0)) labels)) labels))
	 0))

(define (find-labels best labels table-matrix num-peaks)
 (let* ((v (length labels))
	(num-labels (vector-length (third (matrix-ref table-matrix 0 0))))
	(max-label-to-consider
	 (if (null? labels)
	     0
	     (min (+ 1 (maximum labels)) (- num-labels 1))))
	)
  (if (= v num-peaks)
      (list (score labels table-matrix) labels) ;;done
      (let* ((scores (map-n (lambda (i)
			     (list i (best-possible
				      (append labels (list i))
				      table-matrix
				      num-peaks)))
			    (+ max-label-to-consider 1)))
	     (sorted-scores (sort scores < second))
	     (bars
	      (let loop ((scored-labels sorted-scores)
			 (best best))
	       (if (null? scored-labels)
		   '()
		   (if (< (second (first scored-labels)) best)
		       (let* ((answer
			       (find-labels best
					    (append labels
						    (list
						     (first (first scored-labels))))
					    table-matrix
					    num-peaks))
			      (new-best
			       (if (< (first answer) best)
	       			   (first answer)
	       			   best)))
			(cons answer
			      (loop (rest scored-labels) new-best)))
		       (begin
			;; (dtrace "PRUNED"
			;; 	(format #f "score ~a best ~a num-labels ~a"
			;; 		(second (first scored-labels))
			;; 		best v))
			(loop (rest scored-labels) best))))
	       )))
       (if (null? bars)
	   (list infinity '())
	   (first (sort bars < first)))))))







;;;;;NEED TO REBUILD THIS AS A STAND-ALONE PROCEDURE
;; (define-command
;;  (main (at-most-one
;; 	("video" video? (video-path "video-path" string-argument "")))
;;        (at-most-one
;; 	("top-k" top-k? (top-k "top-k" integer-argument 100)))
;;        (at-most-one
;; 	("downsample" downsample? (downsample "rate" integer-argument 1)))
;;        (at-most-one
;; 	("box-size" box-size? (box-size "size" integer-argument 64))))
;;  (let* ((proposals-similarity (proposals&similarity top-k box-size downsample video-path))
;; 	(proposals (map (lambda (boxes) (map (lambda (x) (but-last x))
;; 					     (matrix->list-of-lists boxes)))
;; 			(first proposals-similarity)))
;; 	(f (map (lambda (boxes) (map fifth (matrix->list-of-lists boxes)))
;; 		(first proposals-similarity)))
;; 	(g (map (lambda (sim) (matrix->list-of-lists sim))
;; 		(second proposals-similarity)))
;; 	(f-c (easy-ffi:double-to-c 2 f))
;; 	(g-c (easy-ffi:double-to-c 3 g))
;; 	(boxes-c (list->c-exact-array (malloc (* c-sizeof-int (length f)))
;; 				      (map-n (lambda _ 0) (length f))
;; 				      c-sizeof-int #t))	
;; 	(score (bp-object-inference f-c g-c (length f) top-k boxes-c))
;; 	(boxes (c-exact-array->list boxes-c c-sizeof-int (length f) #t)))
;;   (free boxes-c)
;;   (easy-ffi:free 2 f f-c)
;;   (easy-ffi:free 3 g g-c)
;;   (pp (map (lambda (b pool) (list b (list-ref pool b)))
;; 	   boxes proposals))))
       
