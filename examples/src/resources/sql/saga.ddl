-- Copyright 2019 Kopaniev Vladyslav
-- 
-- Copyright 2024 Jack C. Viers
-- 
-- Permission is hereby granted, free of charge, to any person obtaining a copy of
-- this software and associated documentation files (the "Software"), to deal in
-- the Software without restriction, including without limitation the rights to
-- use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
-- the Software, and to permit persons to whom the Software is furnished to do so,
-- subject to the following conditions:
-- 
-- The above copyright notice and this permission notice shall be included in all
-- copies or substantial portions of the Software.
-- 
-- THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
-- IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
-- FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
-- COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
-- IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
-- CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

CREATE TABLE public.saga
(
    id sequence,
    initiator uuid NOT NULL,
    "createdAt" timestamp without time zone NOT NULL,
    "finishedAt" timestamp without time zone,
    data jsonb NOT NULL,
    type text,
    CONSTRAINT "Saga_pkey" PRIMARY KEY (id)
)

CREATE TABLE public.saga_step
(
    "sagaId" integer NOT NULL,
    name text NOT NULL,
    result jsonb,
    "finishedAt" timestamp without time zone,
    failure text,
    CONSTRAINT saga_step_pkey PRIMARY KEY ("sagaId", name),
    CONSTRAINT saga_id_fk FOREIGN KEY ("sagaId")
        REFERENCES public.saga (id) MATCH SIMPLE
        ON UPDATE NO ACTION
        ON DELETE CASCADE
)